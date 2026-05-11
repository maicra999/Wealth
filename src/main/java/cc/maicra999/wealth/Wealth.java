package cc.maicra999.wealth;

import cc.maicra999.wealth.util.Colors;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.thenextlvl.service.economy.Account;
import net.thenextlvl.service.economy.EconomyController;
import net.thenextlvl.service.economy.TransactionResult;
import net.thenextlvl.service.economy.currency.Currency;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("UnstableApiUsage")
public class Wealth extends JavaPlugin implements Listener {

    private static final NamespacedKey COIN_ITEM_KEY = new NamespacedKey("wealth", "coin_item");
    private static final Component COIN_SYMBOL = Component.text("◎", Colors.MUSTARD);
    private static final DecimalFormat COIN_FORMAT = new DecimalFormat("0.##");
    private static final float[] SOUND_PITCHES = {1f, 1.059f, 1.189f, 1.334f, 1.189f};
    private static final Random RANDOM = new Random();

    private static final int HOLD_TICKS_THRESHOLD = 13;
    private static final int WITHDRAW_START_TICKS = 15;

    private static ItemStack coinItem;

    private final Map<UUID, BukkitTask> balanceViewTasks = new HashMap<>();
    private final Map<UUID, Integer> holdThresholds = new HashMap<>();
    private final Set<UUID> coinTipShown = new HashSet<>();

    private EconomyController economy;

    @Override
    public void onEnable() {
        makeCoinItem();
        getServer().getPluginManager().registerEvents(this, this);

        RegisteredServiceProvider<EconomyController> provider =
                getServer().getServicesManager().getRegistration(EconomyController.class);
        if (provider == null) {
            throw new IllegalStateException("EconomyController service not found");
        }

        economy = provider.getProvider();

        registerCommand("givecoin", new GiveCoinCommand());
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (!player.isSneaking() && item != null) {
            if (!item.getPersistentDataContainer().has(COIN_ITEM_KEY, PersistentDataType.BOOLEAN)) {
                return;
            }

            int amount = item.getAmount();

            event.setCancelled(true);
            consumeHeldItem(player, item);

            player.playSound(player, Sound.ITEM_BUNDLE_INSERT, 0.8f, 0.8f + RANDOM.nextFloat() * 0.4F);
            player.playSound(
                    player,
                    Sound.BLOCK_NOTE_BLOCK_CHIME,
                    0.3f,
                    SOUND_PITCHES[(int) Math.floor(RANDOM.nextFloat() * SOUND_PITCHES.length)]);

            economy.resolveAccount(player.getUniqueId()).thenAccept(optional -> {
                if (optional.isEmpty()) {
                    return;
                }
                Account account = optional.get();

                Currency currency = economy.getCurrencyController().getDefaultCurrency();
                TransactionResult result = account.deposit(amount, currency);

                if (result.successful()) {
                    showBalance(player, account);
                } else {
                    player.sendMessage(Component.text("コインの入金に失敗しました").color(Colors.RED_LIGHT));
                    player.give(getCoinItem().asQuantity(amount));
                }
            });
        } else {
            ItemStack mainItem = player.getInventory().getItemInMainHand();
            if (mainItem.isEmpty()
                    || mainItem.getPersistentDataContainer().has(COIN_ITEM_KEY, PersistentDataType.BOOLEAN)) {
                holdThresholds.put(player.getUniqueId(), HOLD_TICKS_THRESHOLD);
            }
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (event.isSneaking()) {
            if (balanceViewTasks.containsKey(player.getUniqueId())) {
                return;
            }

            holdThresholds.remove(player.getUniqueId());

            BukkitTask task = new BukkitRunnable() {
                private Account account;

                private int holdTicks = 0;
                private int ticks = 0;
                private int withdrawnAmount = 0;

                @Override
                public void run() {
                    if (account == null) {
                        economy.resolveAccount(player.getUniqueId()).thenAccept(optional -> {
                            if (optional.isEmpty()) {
                                cancel();
                                return;
                            }
                            account = optional.get();
                        });
                        return;
                    }

                    int value = holdThresholds.merge(player.getUniqueId(), -1, Integer::sum);
                    if (value > 0) {
                        holdTicks++;
                    } else {
                        holdTicks = 0;
                    }

                    if (holdTicks > WITHDRAW_START_TICKS) {
                        ItemStack mainItem = player.getInventory().getItemInMainHand();

                        if (mainItem.isEmpty()
                                || mainItem.getPersistentDataContainer()
                                        .has(COIN_ITEM_KEY, PersistentDataType.BOOLEAN)) {
                            Currency currency = economy.getCurrencyController().getDefaultCurrency();
                            BigDecimal balance = account.getBalance(currency);

                            int withdrawalSpeed = getWithdrawalSpeed(withdrawnAmount);

                            int oldCount = mainItem.isEmpty() ? 0 : mainItem.getAmount();
                            int newCount = mainItem.isEmpty()
                                    ? withdrawalSpeed
                                    : Math.min(
                                            mainItem.getAmount() + Math.min(withdrawalSpeed, balance.intValue()), 64);
                            int toWithdraw = newCount - oldCount;

                            if (toWithdraw > 0 && balance.compareTo(BigDecimal.valueOf(toWithdraw)) >= 0) {
                                TransactionResult result = account.withdraw(toWithdraw, currency);
                                if (result.successful()) {
                                    ItemStack toSet = mainItem.isEmpty() ? getCoinItem() : mainItem;
                                    toSet.setAmount(newCount);
                                    player.getInventory().setItemInMainHand(toSet);
                                    player.swingMainHand();
                                    showBalance(player, account);
                                    ticks = 0;
                                    withdrawnAmount += toWithdraw;

                                    float progress = newCount / 64f;
                                    player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 0.45f, 0.5f + progress * 1.5f);
                                } else {
                                    player.sendMessage(
                                            Component.text("コインの引き出しに失敗しました").color(Colors.RED_LIGHT));
                                }
                            }
                        }
                    } else {
                        ticks++;
                        if (ticks < 60) {
                            showBalance(player, account);
                        } else if (ticks < 120) {
                            ItemStack mainItem = player.getInventory().getItemInMainHand();

                            if (mainItem.isEmpty()
                                    || mainItem.getPersistentDataContainer()
                                            .has(COIN_ITEM_KEY, PersistentDataType.BOOLEAN)) {
                                player.sendActionBar(Component.textOfChildren(
                                        Component.text("[使用/設置]", Colors.GRAY_LIGHT),
                                        Component.space(),
                                        Component.text("を長押しして財布から出す")));
                            } else {
                                showBalance(player, account);
                            }
                        } else {
                            ticks = 0;
                        }
                    }
                }
            }.runTaskTimer(this, 0L, 1L);
            balanceViewTasks.put(player.getUniqueId(), task);
        } else {
            BukkitTask task = balanceViewTasks.remove(player.getUniqueId());
            if (task != null) {
                task.cancel();
                player.sendActionBar(Component.empty());
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getItemInHand().getPersistentDataContainer().has(COIN_ITEM_KEY, PersistentDataType.BOOLEAN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickupItem(PlayerAttemptPickupItemEvent event) {
        if (event.getItem()
                .getItemStack()
                .getPersistentDataContainer()
                .has(COIN_ITEM_KEY, PersistentDataType.BOOLEAN)) {
            if (coinTipShown.add(event.getPlayer().getUniqueId())) {
                event.getPlayer()
                        .sendMessage(Component.textOfChildren(
                                Component.text("コインを手に持って"),
                                Component.space(),
                                Component.text("[使用する]", Colors.GRAY_LIGHT),
                                Component.space(),
                                Component.text("と、財布にしまうことができます")));
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        BukkitTask task = balanceViewTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }

        holdThresholds.remove(player.getUniqueId());
        coinTipShown.remove(player.getUniqueId());
    }

    private CompletableFuture<Void> showBalance(Player player) {
        return economy.resolveAccount(player.getUniqueId())
                .thenAccept(account -> account.ifPresent(value -> showBalance(player, value)));
    }

    private void showBalance(Player player, Account account) {
        Currency currency = economy.getCurrencyController().getDefaultCurrency();
        player.sendActionBar(Component.textOfChildren(
                COIN_SYMBOL, Component.space(), Component.text(COIN_FORMAT.format(account.getBalance(currency)))));
    }

    private static void consumeHeldItem(Player player, ItemStack item) {
        if (item.equals(player.getInventory().getItemInMainHand())) {
            player.getInventory().setItemInMainHand(null);
            player.swingMainHand();
        } else if (item.equals(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
            player.swingOffHand();
        }
    }

    private static int getWithdrawalSpeed(int withdrawnAmount) {
        if (withdrawnAmount < 64) {
            return 1;
        } else if (withdrawnAmount < 128) {
            return 2;
        } else {
            return 4;
        }
    }

    private static void makeCoinItem() {
        coinItem = new ItemStack(Material.SUNFLOWER);
        coinItem.setData(DataComponentTypes.ITEM_NAME, Component.text("コイン"));
        coinItem.setData(DataComponentTypes.RARITY, ItemRarity.UNCOMMON);
        coinItem.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        coinItem.editPersistentDataContainer(pdc -> pdc.set(COIN_ITEM_KEY, PersistentDataType.BOOLEAN, true));
    }

    private static ItemStack getCoinItem() {
        return coinItem.clone();
    }

    static class GiveCoinCommand implements BasicCommand {

        @Override
        public @Nullable String permission() {
            return "wealth.command.givecoin";
        }

        @Override
        public void execute(@NonNull CommandSourceStack source, String[] args) {
            if (args.length != 1) {
                source.getSender()
                        .sendMessage(Component.text("使用法: /givecoin <数量>").color(Colors.RED_LIGHT));
                return;
            }

            int amount;
            try {
                amount = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                source.getSender().sendMessage(Component.text("無効な数量").color(Colors.RED_LIGHT));
                return;
            }

            if (amount <= 0) {
                source.getSender()
                        .sendMessage(Component.text("数値は正の整数である必要があります").color(Colors.RED_LIGHT));
                return;
            }

            List<ItemStack> stacks = new ArrayList<>();
            while (amount > 0) {
                int stackAmount = Math.min(amount, 64);
                ItemStack stack = getCoinItem();
                stack.setAmount(stackAmount);
                stacks.add(stack);
                amount -= stackAmount;
            }

            if (source.getSender() instanceof Player player) {
                for (ItemStack stack : stacks) {
                    player.give(stack);
                }
                player.sendMessage(Component.text(args[0] + "コインを付与しました").color(Colors.GREEN));
            } else {
                source.getSender().sendMessage(Component.text("コインを付与できません").color(Colors.RED_LIGHT));
            }
        }
    }
}

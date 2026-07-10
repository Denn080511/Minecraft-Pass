package com.example.royalcrown;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.Material;
import org.bukkit.ChatColor;

public class RoyalCrownPlugin extends JavaPlugin implements CommandExecutor {

    private final Set<UUID> crowned = new HashSet<>();
    private NamespacedKey crownKey;
    private NamespacedKey swordKey;
    private BukkitTask task;

    // Config constants
    private static final int TICK_INTERVAL = 10; // runs every 10 ticks (0.5s)
    private static final int STRENGTH_DURATION_TICKS = 40; // give effect for 2s then refresh
    private static final int STRENGTH_AMPLIFIER = 0; // 0 = Strength I

    @Override
    public void onEnable() {
        crownKey = new NamespacedKey(this, "royal_crown");
        swordKey = new NamespacedKey(this, "kings_sword");

        if (getCommand("givecrown") != null) getCommand("givecrown").setExecutor(this);

        // Start repeating task to check equip state
        task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    scanPlayer(p);
                } catch (Exception e) {
                    getLogger().severe("Error scanning player " + p.getName() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }, 0L, TICK_INTERVAL);
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        crowned.clear();
    }

    private void scanPlayer(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack helmet = inv.getHelmet();
        boolean wearing = isCrown(helmet);

        UUID id = p.getUniqueId();
        boolean previously = crowned.contains(id);

        if (wearing && !previously) {
            onEquip(p);
        } else if (!wearing && previously) {
            onUnequip(p);
        }

        if (wearing) {
            applyContinuousEffects(p);
        }
    }

    private boolean isCrown(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc != null && pdc.has(crownKey, PersistentDataType.BYTE);
    }

    private void onEquip(Player p) {
        crowned.add(p.getUniqueId());
        p.sendMessage(ChatColor.GOLD + "You have been crowned!");

        // Give King's Sword if player doesn't already have one
        if (!hasKingsSword(p)) {
            giveKingsSword(p);
        }
    }

    private void onUnequip(Player p) {
        crowned.remove(p.getUniqueId());
        p.sendMessage(ChatColor.GRAY + "Your crown has been removed.");

        // Remove Strength effect immediately
        p.removePotionEffect(PotionEffectType.INCREASE_DAMAGE);

        // Remove King's Sword(s) that we issued
        removeKingsSword(p);
    }

    private void applyContinuousEffects(Player p) {
        // Apply Strength that is refreshed by the repeating task
        p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, STRENGTH_DURATION_TICKS, STRENGTH_AMPLIFIER, true, false, true));
    }

    private boolean hasKingsSword(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null) continue;
            ItemMeta m = it.getItemMeta();
            if (m == null) continue;
            PersistentDataContainer pdc = m.getPersistentDataContainer();
            if (pdc != null && pdc.has(swordKey, PersistentDataType.BYTE)) return true;
        }
        return false;
    }

    private void giveKingsSword(Player p) {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "King's Sword");
        meta.addEnchant(Enchantment.DAMAGE_ALL, 5, true);
        meta.addEnchant(Enchantment.DURABILITY, 3, true);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        // Tag the sword so we can find it again
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(swordKey, PersistentDataType.BYTE, (byte)1);

        // Optionally add attack damage via AttributeModifier (extra +5)
        AttributeModifier extra = new AttributeModifier(UUID.randomUUID(), "king_damage", 5.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HAND);
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, extra);

        sword.setItemMeta(meta);

        boolean added = false;
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            if (p.getInventory().getItem(i) == null) {
                p.getInventory().setItem(i, sword);
                added = true;
                break;
            }
        }

        if (!added) {
            p.getWorld().dropItemNaturally(p.getLocation(), sword);
            p.sendMessage(ChatColor.YELLOW + "Your King's Sword was dropped because your inventory was full.");
        } else {
            p.sendMessage(ChatColor.GREEN + "The King's Sword has been granted to you.");
        }
    }

    private void removeKingsSword(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            ItemMeta meta = it.getItemMeta();
            if (meta == null) continue;
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (pdc != null && pdc.has(swordKey, PersistentDataType.BYTE)) {
                inv.clear(i);
            }
        }
    }

    // /givecrown [player] - gives the crown item
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("givecrown")) return false;

        if (!sender.hasPermission("royalcrown.give") && !sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        Player target;
        if (args.length >= 1) {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
        } else {
            if (sender instanceof Player) target = (Player) sender;
            else {
                sender.sendMessage(ChatColor.RED + "Specify a player from console.");
                return true;
            }
        }

        ItemStack crown = createCrownItem();
        target.getInventory().addItem(crown);
        sender.sendMessage(ChatColor.GREEN + "Gave crown to " + target.getName());
        return true;
    }

    private ItemStack createCrownItem() {
        ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
        ItemMeta meta = helmet.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Royal Crown");
        // Example: add a health bonus via AttributeModifier on the helmet (applies when worn)
        AttributeModifier healthBonus = new AttributeModifier(UUID.randomUUID(), "crown_health", 4.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HEAD);
        meta.addAttributeModifier(Attribute.GENERIC_MAX_HEALTH, healthBonus);

        // Set a custom-model-data value so a resource pack can override the model (optional)
        try {
            meta.setCustomModelData(123456);
        } catch (NoSuchMethodError ignored) {
            // older servers may not support CustomModelData; ignore
        }

        // Mark it with PDC so plugin recognizes it definitively
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(crownKey, PersistentDataType.BYTE, (byte)1);

        helmet.setItemMeta(meta);
        return helmet;
    }
}

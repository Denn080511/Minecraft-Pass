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
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
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
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public class RoyalCrownPlugin extends JavaPlugin implements CommandExecutor, Listener {

    private final Set<UUID> crowned = new HashSet<>();
    private NamespacedKey crownKey;
    private NamespacedKey swordKey;
    private NamespacedKey axeKey;
    private NamespacedKey shieldKey;
    private NamespacedKey bowKey;
    private NamespacedKey maceKey;
    private BukkitTask task;

    // Config constants
    private static final int TICK_INTERVAL = 10; // runs every 10 ticks (0.5s)
    private static final int EFFECT_DURATION_TICKS = 100; // 5s
    // Strength III -> amplifier 2 (0 = I)
    private static final int STRENGTH_AMPLIFIER = 2;

    @Override
    public void onEnable() {
        crownKey = new NamespacedKey(this, "royal_crown");
        swordKey = new NamespacedKey(this, "kings_sword");
        axeKey = new NamespacedKey(this, "kings_axe");
        shieldKey = new NamespacedKey(this, "kings_shield");
        bowKey = new NamespacedKey(this, "kings_bow");
        maceKey = new NamespacedKey(this, "kings_mace");

        if (getCommand("givecrown") != null) getCommand("givecrown").setExecutor(this);

        getServer().getPluginManager().registerEvents(this, this);

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

        // Give Royal Arsenal (and equip main/offhand)
        giveRoyalArsenal(p);
    }

    private void onUnequip(Player p) {
        crowned.remove(p.getUniqueId());
        p.sendMessage(ChatColor.GRAY + "Your crown has been removed.");

        // Remove potion effects
        p.removePotionEffect(PotionEffectType.INCREASE_DAMAGE);
        p.removePotionEffect(PotionEffectType.SPEED);
        p.removePotionEffect(PotionEffectType.DAMAGE_RESISTANCE);
        p.removePotionEffect(PotionEffectType.FAST_DIGGING);
        p.removePotionEffect(PotionEffectType.JUMP);
        p.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
        p.removePotionEffect(PotionEffectType.REGENERATION);
        p.removePotionEffect(PotionEffectType.SATURATION);

        // Remove Royal Arsenal items we gave
        removeTaggedItems(p, swordKey);
        removeTaggedItems(p, axeKey);
        removeTaggedItems(p, shieldKey);
        removeTaggedItems(p, bowKey);
        removeTaggedItems(p, maceKey);
    }

    private void applyContinuousEffects(Player p) {
        // Apply all effects and refresh them frequently (task will refresh)
        p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, EFFECT_DURATION_TICKS, STRENGTH_AMPLIFIER, true, false, true)); // Strength III
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, EFFECT_DURATION_TICKS, 1, true, false, true)); // Speed II
        p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, EFFECT_DURATION_TICKS, 1, true, false, true)); // Resistance II
        p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, EFFECT_DURATION_TICKS, 1, true, false, true)); // Haste II
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, EFFECT_DURATION_TICKS, 1, true, false, true)); // Jump Boost II
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, EFFECT_DURATION_TICKS, 0, true, false, true)); // Fire Resistance
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, EFFECT_DURATION_TICKS, 0, true, false, true)); // Regeneration I
        p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, EFFECT_DURATION_TICKS, 0, true, false, true)); // Saturation
    }

    private void giveRoyalArsenal(Player p) {
        PlayerInventory inv = p.getInventory();

        // Create items
        ItemStack sword = createRoyalSword();
        ItemStack axe = createRoyalAxe();
        ItemStack shield = createRoyalShield();
        ItemStack bow = createRoyalBow();
        ItemStack mace = createRoyalMace();

        // Give inventory: try to put them into main inventory
        inv.addItem(sword);
        inv.addItem(axe);
        inv.addItem(bow);
        inv.addItem(mace);

        // Equip sword in main hand and shield in offhand if possible
        inv.setItemInMainHand(sword);
        inv.setItemInOffHand(shield);

        p.sendMessage(ChatColor.GREEN + "The King's Arsenal has been granted to you.");
    }

    private ItemStack createRoyalSword() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "King's Sword");
        meta.addEnchant(Enchantment.DAMAGE_ALL, 5, true); // Sharpness V
        meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        meta.addEnchant(Enchantment.LOOT_BONUS_MOBS, 3, true);
        meta.addEnchant(Enchantment.DURABILITY, 3, true);
        try { meta.addEnchant(Enchantment.MENDING, 1, true); } catch (Throwable ignored) {}
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        // Tag the sword so we can find it again
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(swordKey, PersistentDataType.BYTE, (byte)1);

        // Attack damage + attack speed attributes
        AttributeModifier dam = new AttributeModifier(UUID.randomUUID(), "king_damage", 15.0, Operation.ADD_NUMBER, EquipmentSlot.HAND);
        AttributeModifier speed = new AttributeModifier(UUID.randomUUID(), "king_attack_speed", -2.4, Operation.ADD_NUMBER, EquipmentSlot.HAND);
        // Note: attack speed is relative; vanilla swords have base attack speed ~1.6, player base 4.0; using negative to lower to ~1.6

        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, dam);
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED, speed);

        sword.setItemMeta(meta);
        return sword;
    }

    private ItemStack createRoyalAxe() {
        ItemStack axe = new ItemStack(Material.NETHERITE_AXE);
        ItemMeta meta = axe.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Royal Battle Axe");
        meta.addEnchant(Enchantment.DAMAGE_ALL, 5, true); // Sharpness V
        meta.addEnchant(Enchantment.DIG_SPEED, 5, true); // Efficiency V for flavor
        meta.addEnchant(Enchantment.DURABILITY, 3, true);
        try { meta.addEnchant(Enchantment.MENDING, 1, true); } catch (Throwable ignored) {}
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(axeKey, PersistentDataType.BYTE, (byte)1);

        AttributeModifier dam = new AttributeModifier(UUID.randomUUID(), "axe_damage", 20.0, Operation.ADD_NUMBER, EquipmentSlot.HAND);
        AttributeModifier speed = new AttributeModifier(UUID.randomUUID(), "axe_attack_speed", -3.0, Operation.ADD_NUMBER, EquipmentSlot.HAND); // heavy, slow
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, dam);
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED, speed);

        axe.setItemMeta(meta);
        return axe;
    }

    private ItemStack createRoyalShield() {
        ItemStack shield = new ItemStack(Material.SHIELD);
        ItemMeta meta = shield.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Royal Shield");
        meta.addEnchant(Enchantment.DURABILITY, 3, true);
        try { meta.addEnchant(Enchantment.MENDING, 1, true); } catch (Throwable ignored) {}
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(shieldKey, PersistentDataType.BYTE, (byte)1);

        shield.setItemMeta(meta);
        return shield;
    }

    private ItemStack createRoyalBow() {
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta meta = bow.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Royal Bow");
        meta.addEnchant(Enchantment.ARROW_DAMAGE, 5, true); // Power V
        meta.addEnchant(Enchantment.ARROW_KNOCKBACK, 2, true); // Punch II
        meta.addEnchant(Enchantment.ARROW_FIRE, 1, true); // Flame
        meta.addEnchant(Enchantment.ARROW_INFINITE, 1, true); // Infinity
        meta.addEnchant(Enchantment.DURABILITY, 3, true);
        try { meta.addEnchant(Enchantment.MENDING, 1, true); } catch (Throwable ignored) {}
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(bowKey, PersistentDataType.BYTE, (byte)1);

        bow.setItemMeta(meta);
        return bow;
    }

    private ItemStack createRoyalMace() {
        // Use netherite_axe as a mace stand-in
        ItemStack mace = new ItemStack(Material.NETHERITE_AXE);
        ItemMeta meta = mace.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Royal Mace");
        meta.addEnchant(Enchantment.DAMAGE_ALL, 5, true); // Sharpness V
        meta.addEnchant(Enchantment.DURABILITY, 3, true);
        try { meta.addEnchant(Enchantment.MENDING, 1, true); } catch (Throwable ignored) {}
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(maceKey, PersistentDataType.BYTE, (byte)1);

        AttributeModifier dam = new AttributeModifier(UUID.randomUUID(), "mace_damage", 6.0, Operation.ADD_NUMBER, EquipmentSlot.HAND);
        AttributeModifier speed = new AttributeModifier(UUID.randomUUID(), "mace_attack_speed", -3.0, Operation.ADD_NUMBER, EquipmentSlot.HAND);
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, dam);
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED, speed);

        mace.setItemMeta(meta);
        return mace;
    }

    private void removeTaggedItems(Player p, NamespacedKey key) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            ItemMeta m = it.getItemMeta();
            if (m == null) continue;
            PersistentDataContainer pdc = m.getPersistentDataContainer();
            if (pdc != null && pdc.has(key, PersistentDataType.BYTE)) {
                inv.clear(i);
            }
        }

        // Also check main hand and offhand
        ItemStack main = inv.getItemInMainHand();
        if (main != null && main.hasItemMeta()) {
            ItemMeta m = main.getItemMeta();
            if (m.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) inv.setItemInMainHand(null);
        }
        ItemStack off = inv.getItemInOffHand();
        if (off != null && off.hasItemMeta()) {
            ItemMeta m = off.getItemMeta();
            if (m.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) inv.setItemInOffHand(null);
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
        meta.setDisplayName(ChatColor.GOLD + "King's Crown");

        // Health bonus via AttributeModifier (applies when worn)
        AttributeModifier healthBonus = new AttributeModifier(UUID.randomUUID(), "crown_health", 10.0, Operation.ADD_NUMBER, EquipmentSlot.HEAD);
        meta.addAttributeModifier(Attribute.GENERIC_MAX_HEALTH, healthBonus);

        // Tag it with PDC so plugin recognizes it definitively
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(crownKey, PersistentDataType.BYTE, (byte)1);

        // Optional model data
        try {
            meta.setCustomModelData(123456);
        } catch (NoSuchMethodError ignored) {}

        helmet.setItemMeta(meta);
        return helmet;
    }

    // Event handlers for special weapon behavior
    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player attacker = (Player) e.getDamager();
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon == null || !weapon.hasItemMeta()) return;
        ItemMeta meta = weapon.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        double damage = e.getDamage();

        // Sword sweeping: deal full damage to nearby enemies
        if (pdc.has(swordKey, PersistentDataType.BYTE)) {
            for (Entity ent : e.getEntity().getNearbyEntities(3, 2, 3)) {
                if (ent instanceof LivingEntity && ent != attacker && ent != e.getEntity()) {
                    LivingEntity le = (LivingEntity) ent;
                    le.damage(damage, attacker);
                }
            }
        }

        // Axe: disable shields on victims for 8 seconds
        if (pdc.has(axeKey, PersistentDataType.BYTE)) {
            if (e.getEntity() instanceof Player) {
                Player victim = (Player) e.getEntity();
                try {
                    victim.setCooldown(Material.SHIELD, 8 * 20); // disable for 8 seconds
                } catch (NoSuchMethodError ex) {
                    // older API: ignore if not available
                }
            }
        }

        // Mace: extra smash damage based on attacker's fall distance (only if valid)
        if (pdc.has(maceKey, PersistentDataType.BYTE)) {
            if (attacker instanceof Player) {
                double fall = attacker.getFallDistance();
                if (fall > 0) {
                    double bonus = fall * 2.0; // tunable multiplier
                    if (e.getEntity() instanceof LivingEntity) {
                        ((LivingEntity) e.getEntity()).damage(bonus, attacker);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        Projectile proj = e.getEntity();
        if (!(proj.getShooter() instanceof Player)) return;
        Player shooter = (Player) proj.getShooter();

        ItemStack bow = shooter.getInventory().getItemInMainHand();
        if (bow == null || !bow.hasItemMeta()) return;
        ItemMeta meta = bow.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        if (pdc.has(bowKey, PersistentDataType.BYTE) && proj instanceof Arrow) {
            Arrow arrow = (Arrow) proj;
            // set arrow damage high (tunable). Here we set strong damage to approximate ~25 before armor.
            arrow.setDamage(12.5); // tunable; Spigot uses half-hearts in some contexts
            arrow.setKnockbackStrength(2);
            arrow.setFireTicks(100);
        }
    }
}

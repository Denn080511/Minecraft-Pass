package com.example.royalcrown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.RecipeChoice.ExactChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.Container;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.Material;

public class RoyalCrownPlugin extends JavaPlugin implements CommandExecutor, Listener {

    private final Set<UUID> crowned = new HashSet<>();
    private NamespacedKey crownKey;
    private NamespacedKey swordKey;
    private NamespacedKey axeKey;
    private NamespacedKey shieldKey;
    private NamespacedKey bowKey;
    private NamespacedKey maceKey;
    private NamespacedKey ownerKey; // owner UUID stored as STRING
    private NamespacedKey soulsKey; // optional souls on crown/item

    // Drop return scheduling
    private final Map<UUID, BukkitTask> dropReturnTasks = new HashMap<>();
    private final Map<UUID, List<ItemStack>> pendingReturns = new HashMap<>();

    // Config constants
    private static final int TICK_INTERVAL = 20; // runs every 20 ticks (1s)
    private static final int EFFECT_DURATION_TICKS = 2000000000; // very large, effectively "permanent while worn"
    // Strength II -> amplifier 1 (0 = I)
    private static final int STRENGTH_AMPLIFIER = 1;

    // Steal mechanics
    private static final double STEAL_AMOUNT = 1.0; // 1 HP (0.5 heart)
    private static final double DEFAULT_MAX_HEALTH = 20.0; // 10 hearts
    private static final double MAX_ALLOWED_HEALTH = 60.0; // 30 hearts

    @Override
    public void onEnable() {
        crownKey = new NamespacedKey(this, "royal_crown");
        swordKey = new NamespacedKey(this, "kings_sword");
        axeKey = new NamespacedKey(this, "kings_axe");
        shieldKey = new NamespacedKey(this, "kings_shield");
        bowKey = new NamespacedKey(this, "kings_bow");
        maceKey = new NamespacedKey(this, "kings_mace");
        ownerKey = new NamespacedKey(this, "weapon_owner");
        soulsKey = new NamespacedKey(this, "royal_souls");

        if (getCommand("givecrown") != null) getCommand("givecrown").setExecutor(this);

        getServer().getPluginManager().registerEvents(this, this);

        // Start repeating task to check equip state and retry pending returns
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    try {
                        scanPlayer(p);
                    } catch (Exception e) {
                        getLogger().severe("Error scanning player " + p.getName() + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }

                // Try to deliver any pending returned items
                try {
                    processPendingReturns();
                } catch (Exception e) {
                    getLogger().severe("Error processing pending returns: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }.runTaskTimer(this, 0L, TICK_INTERVAL);

        // Register crafting recipe for Prince Crown (broken -> prince)
        registerPrinceRecipe();
    }

    @Override
    public void onDisable() {
        crowned.clear();
        dropReturnTasks.values().forEach(BukkitTask::cancel);
        dropReturnTasks.clear();
        pendingReturns.clear();
    }

    private void registerPrinceRecipe() {
        // This uses the layout you provided (image). We'll create a shaped recipe.
        NamespacedKey key = new NamespacedKey(this, "prince_crown_recipe");

        ItemStack result = createPrinceCrownItem();
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        // Example pattern from the image. We'll use symbols: G = gold_block, N = nether_star? but your image had a totem-like center
        recipe.shape("GxG", "HcA", "GdG");
        // Map characters to materials; adjust according to your design (image unclear). Use placeholders:
        recipe.setIngredient('G', Material.GOLD_BLOCK);
        recipe.setIngredient('x', Material.AIR);
        recipe.setIngredient('H', Material.GOLDEN_HELMET);
        recipe.setIngredient('c', Material.DISPENSER); // you used something like an anvil? adjust as needed
        recipe.setIngredient('A', Material.GOLDEN_APPLE);
        recipe.setIngredient('d', Material.DIAMOND_BLOCK);

        // Add to server
        Bukkit.addRecipe(recipe);
    }

    private ItemStack createPrinceCrownItem() {
        ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
        ItemMeta meta = helmet.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Prince's Crown");
        meta.addEnchant(Enchantment.PROTECTION_ENVIRONMENTAL, 1, true);
        meta.addEnchant(Enchantment.DURABILITY, 2, true);
        meta.setUnbreakable(false);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(crownKey, PersistentDataType.BYTE, (byte)1);
        helmet.setItemMeta(meta);
        return helmet;
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

        // If the crown has no souls counter, initialize it to 0 on the item
        ItemStack helmet = p.getInventory().getHelmet();
        if (helmet != null && helmet.hasItemMeta()) {
            ItemMeta meta = helmet.getItemMeta();
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (!pdc.has(soulsKey, PersistentDataType.INTEGER)) {
                pdc.set(soulsKey, PersistentDataType.INTEGER, 0);
                helmet.setItemMeta(meta);
                p.getInventory().setHelmet(helmet);
            }
        }

        // Give Royal Arsenal (and equip main/offhand) if player doesn't have them already
        giveRoyalArsenalIfMissing(p);
    }

    private void onUnequip(Player p) {
        crowned.remove(p.getUniqueId());
        p.sendMessage(ChatColor.GRAY + "Your crown has been removed.");

        // Remove potion effects (they'll be re-applied when worn)
        p.removePotionEffect(PotionEffectType.INCREASE_DAMAGE);
        p.removePotionEffect(PotionEffectType.SPEED);
        p.removePotionEffect(PotionEffectType.DAMAGE_RESISTANCE);
        p.removePotionEffect(PotionEffectType.FAST_DIGGING);
        p.removePotionEffect(PotionEffectType.JUMP);
        p.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
        p.removePotionEffect(PotionEffectType.REGENERATION);
        p.removePotionEffect(PotionEffectType.SATURATION);
    }

    private void applyContinuousEffects(Player p) {
        // Permanent while worn effects
        p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, EFFECT_DURATION_TICKS, STRENGTH_AMPLIFIER, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, EFFECT_DURATION_TICKS, 1, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, EFFECT_DURATION_TICKS, 1, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, EFFECT_DURATION_TICKS, 1, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, EFFECT_DURATION_TICKS, 1, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, EFFECT_DURATION_TICKS, 0, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, EFFECT_DURATION_TICKS, 0, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, EFFECT_DURATION_TICKS, 0, true, false, true));
    }

    private void giveRoyalArsenalIfMissing(Player p) {
        PlayerInventory inv = p.getInventory();

        // Create items
        ItemStack sword = createRoyalSword();
        ItemStack axe = createRoyalAxe();
        ItemStack shield = createRoyalShield();
        ItemStack bow = createRoyalBow();
        ItemStack mace = createRoyalMace();

        // Tag ownership: set owner to this player for each created item
        setOwnerOnItem(sword, p.getUniqueId());
        setOwnerOnItem(axe, p.getUniqueId());
        setOwnerOnItem(shield, p.getUniqueId());
        setOwnerOnItem(bow, p.getUniqueId());
        setOwnerOnItem(mace, p.getUniqueId());

        // Give inventory: try to put them into main inventory if not present
        inv.addItem(sword);
        inv.addItem(axe);
        inv.addItem(bow);
        inv.addItem(mace);

        // Equip sword in main hand and shield in offhand if possible
        inv.setItemInMainHand(sword);
        inv.setItemInOffHand(shield);

        p.sendMessage(ChatColor.GREEN + "The King's Arsenal has been granted to you.");
    }

    private void setOwnerOnItem(ItemStack item, UUID owner) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        item.setItemMeta(meta);
    }

    private ItemStack createRoyalSword() {
        ItemStack sword = new ItemStack(Material.GOLDEN_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Swordlessed");
        meta.addEnchant(Enchantment.DAMAGE_ALL, 1, true); // Sharpness I for prince stage
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        meta.setUnbreakable(false);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(swordKey, PersistentDataType.BYTE, (byte)1);

        // modest attack damage attribute
        AttributeModifier dam = new AttributeModifier(UUID.randomUUID(), "king_damage", 3.0, Operation.ADD_NUMBER, EquipmentSlot.HAND);
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, dam);

        sword.setItemMeta(meta);
        return sword;
    }

    private ItemStack createRoyalAxe() {
        ItemStack axe = new ItemStack(Material.IRON_AXE);
        ItemMeta meta = axe.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Iron Clad Axe");
        meta.addEnchant(Enchantment.DAMAGE_ALL, 1, true);
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        meta.setUnbreakable(false);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(axeKey, PersistentDataType.BYTE, (byte)1);

        AttributeModifier dam = new AttributeModifier(UUID.randomUUID(), "axe_damage", 6.0, Operation.ADD_NUMBER, EquipmentSlot.HAND);
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, dam);

        axe.setItemMeta(meta);
        return axe;
    }

    private ItemStack createRoyalShield() {
        ItemStack shield = new ItemStack(Material.SHIELD);
        ItemMeta meta = shield.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Royal Shield");
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        meta.setUnbreakable(false);
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
        meta.addEnchant(Enchantment.ARROW_DAMAGE, 1, true);
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        meta.setUnbreakable(false);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(bowKey, PersistentDataType.BYTE, (byte)1);

        bow.setItemMeta(meta);
        return bow;
    }

    private ItemStack createRoyalMace() {
        ItemStack mace = new ItemStack(Material.IRON_AXE);
        ItemMeta meta = mace.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Rhopalon");
        meta.addEnchant(Enchantment.DAMAGE_ALL, 1, true);
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        meta.setUnbreakable(false);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(maceKey, PersistentDataType.BYTE, (byte)1);

        AttributeModifier dam = new AttributeModifier(UUID.randomUUID(), "mace_damage", 2.0, Operation.ADD_NUMBER, EquipmentSlot.HAND);
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, dam);

        mace.setItemMeta(meta);
        return mace;
    }

    private void processPendingReturns() {
        List<UUID> toRemove = new ArrayList<>();
        for (Map.Entry<UUID, List<ItemStack>> e : pendingReturns.entrySet()) {
            UUID ownerUuid = e.getKey();
            Player owner = Bukkit.getPlayer(ownerUuid);
            if (owner == null) continue;
            List<ItemStack> list = e.getValue();
            List<ItemStack> notPlaced = new ArrayList<>();
            for (ItemStack stack : list) {
                HashMap<Integer, ItemStack> leftovers = owner.getInventory().addItem(stack);
                if (!leftovers.isEmpty()) {
                    // couldn't place; keep for later
                    notPlaced.addAll(leftovers.values());
                }
            }
            if (notPlaced.isEmpty()) {
                toRemove.add(ownerUuid);
                owner.sendMessage(ChatColor.GREEN + "Your Royal weapon(s) were returned to your inventory.");
            } else {
                pendingReturns.put(ownerUuid, notPlaced);
            }
        }
        for (UUID u : toRemove) pendingReturns.remove(u);
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

        ItemStack crown = createBrokenCrownItem();
        target.getInventory().addItem(crown);
        sender.sendMessage(ChatColor.GREEN + "Gave crown to " + target.getName());
        return true;
    }

    private ItemStack createBrokenCrownItem() {
        ItemStack helmet = new ItemStack(Material.GOLDEN_HELMET);
        ItemMeta meta = helmet.getItemMeta();
        meta.setDisplayName(ChatColor.GRAY + "Broken Crown");
        // intentionally "useless"
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(crownKey, PersistentDataType.BYTE, (byte)1);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        helmet.setItemMeta(meta);
        return helmet;
    }

    // ---- Events ----

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        LivingEntity victim = e.getEntity();
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;

        // Only count kills by crown weapons (main hand with PDC tag)
        ItemStack weapon = killer.getInventory().getItemInMainHand();
        if (weapon == null || !weapon.hasItemMeta()) return;
        ItemMeta meta = weapon.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!(pdc.has(swordKey, PersistentDataType.BYTE) || pdc.has(axeKey, PersistentDataType.BYTE) || pdc.has(maceKey, PersistentDataType.BYTE) || pdc.has(bowKey, PersistentDataType.BYTE))) {
            return; // not a crown weapon
        }

        // Steal health: remove from victim's max health and add to killer's max health
        transferHealthOnKill(killer, victim, STEAL_AMOUNT);

        // Increment souls counter on crown if present
        ItemStack helmet = killer.getInventory().getHelmet();
        if (helmet != null && helmet.hasItemMeta()) {
            ItemMeta hmeta = helmet.getItemMeta();
            PersistentDataContainer hpdc = hmeta.getPersistentDataContainer();
            int souls = 0;
            if (hpdc.has(soulsKey, PersistentDataType.INTEGER)) souls = hpdc.get(soulsKey, PersistentDataType.INTEGER);
            souls++;
            hpdc.set(soulsKey, PersistentDataType.INTEGER, souls);
            helmet.setItemMeta(hmeta);
            killer.getInventory().setHelmet(helmet);
            killer.sendMessage(ChatColor.YELLOW + "Soul captured! (" + souls + ")");
        }
    }

    private void transferHealthOnKill(Player killer, LivingEntity victim, double amount) {
        try {
            // Victim: reduce max health but never below 2.0
            if (victim instanceof Player) {
                Player pv = (Player) victim;
                double vBase = pv.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue();
                double newV = Math.max(2.0, vBase - amount);
                pv.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(newV);
                if (pv.getHealth() > newV) pv.setHealth(newV);
            } else {
                // For non-player mobs, we don't touch max health but try to reduce current health (best effort)
                double cur = victim.getHealth();
                victim.setHealth(Math.max(0.1, cur - amount));
            }

            // Killer: increase max health up to cap
            double kBase = killer.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue();
            double newK = Math.min(MAX_ALLOWED_HEALTH, kBase + amount);
            killer.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(newK);
            // also heal the killer by the amount (but not to exceed max)
            double newHealth = Math.min(killer.getHealth() + amount, newK);
            killer.setHealth(newHealth);

            killer.sendMessage(ChatColor.GREEN + "+" + amount + " HP stolen from your victim!" );
        } catch (Exception ex) {
            getLogger().warning("Error transferring health: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        // Reset their max health back to default
        try {
            dead.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(DEFAULT_MAX_HEALTH);
            if (dead.getHealth() > DEFAULT_MAX_HEALTH) dead.setHealth(DEFAULT_MAX_HEALTH);
        } catch (Exception ex) {
            // ignore
        }

        // Remove any royal weapons owned by this player from all inventories/containers to avoid dupes
        UUID deadId = dead.getUniqueId();
        removeAllWeaponInstances(deadId);

        // If they had pending returns queued, clear them
        pendingReturns.remove(deadId);
    }

    private void removeAllWeaponInstances(UUID owner) {
        String ownerStr = owner.toString();
        // Remove from online players inventories & ender chest
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (it == null || !it.hasItemMeta()) continue;
                ItemMeta m = it.getItemMeta();
                if (m.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
                    String o = m.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                    if (ownerStr.equals(o)) inv.clear(i);
                }
            }
            // main hand/offhand
            ItemStack main = inv.getItemInMainHand();
            if (main != null && main.hasItemMeta()) {
                ItemMeta m = main.getItemMeta();
                if (m.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
                    String o = m.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                    if (ownerStr.equals(o)) inv.setItemInMainHand(null);
                }
            }
            ItemStack off = inv.getItemInOffHand();
            if (off != null && off.hasItemMeta()) {
                ItemMeta m = off.getItemMeta();
                if (m.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
                    String o = m.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                    if (ownerStr.equals(o)) inv.setItemInOffHand(null);
                }
            }

            // ender chest
            try {
                Inventory ender = p.getEnderChest();
                for (int i = 0; i < ender.getSize(); i++) {
                    ItemStack it = ender.getItem(i);
                    if (it == null || !it.hasItemMeta()) continue;
                    ItemMeta m = it.getItemMeta();
                    if (m.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
                        String o = m.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                        if (ownerStr.equals(o)) ender.clear(i);
                    }
                }
            } catch (Exception ex) {
                // ignore
            }
        }

        // Scan loaded chunks inventories (chests, shulkers, dispensers, droppers, etc.)
        Bukkit.getWorlds().forEach(world -> {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof InventoryHolder) {
                        Inventory inv = ((InventoryHolder) state).getInventory();
                        for (int i = 0; i < inv.getSize(); i++) {
                            ItemStack it = inv.getItem(i);
                            if (it == null || !it.hasItemMeta()) continue;
                            ItemMeta m = it.getItemMeta();
                            if (m.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
                                String o = m.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                                if (ownerStr.equals(o)) inv.clear(i);
                            }
                        }
                    }
                }
            }
        });
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        Item dropped = e.getItemDrop();
        ItemStack stack = dropped.getItemStack();
        if (stack == null || !stack.hasItemMeta()) return;
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(ownerKey, PersistentDataType.STRING)) {
            // schedule a return after 30 seconds
            UUID itemUuid = dropped.getUniqueId();
            Player owner = e.getPlayer();
            // Ensure owner recorded
            String ownerStr = pdc.get(ownerKey, PersistentDataType.STRING);
            if (ownerStr == null) ownerStr = owner.getUniqueId().toString();

            // Cancel existing tasks on this item if any
            if (dropReturnTasks.containsKey(itemUuid)) {
                dropReturnTasks.get(itemUuid).cancel();
                dropReturnTasks.remove(itemUuid);
            }

            BukkitTask task = new BukkitRunnable() {
                @Override
                public void run() {
                    if (!dropped.isValid()) return;
                    ItemStack current = dropped.getItemStack();
                    if (current == null) return;
                    ItemMeta cm = current.getItemMeta();
                    if (cm == null) return;
                    PersistentDataContainer cp = cm.getPersistentDataContainer();
                    if (!cp.has(ownerKey, PersistentDataType.STRING)) return;
                    String ownerId = cp.get(ownerKey, PersistentDataType.STRING);
                    UUID ownerUuid = null;
                    try { ownerUuid = UUID.fromString(ownerId); } catch (Exception ex) { return; }

                    Player p = Bukkit.getPlayer(ownerUuid);
                    if (p == null) {
                        // owner offline: queue the item for return later
                        pendingReturns.computeIfAbsent(ownerUuid, k -> new ArrayList<>()).add(current);
                        dropped.remove();
                        return;
                    }

                    HashMap<Integer, ItemStack> leftovers = p.getInventory().addItem(current);
                    if (!leftovers.isEmpty()) {
                        // couldn't place now: queue and remove entity
                        pendingReturns.computeIfAbsent(ownerUuid, k -> new ArrayList<>()).addAll(leftovers.values());
                    }

                    dropped.remove();
                }
            }.runTaskLater(this, 30 * 20L);

            dropReturnTasks.put(itemUuid, task);
        }
    }

    @EventHandler
    public void onEntityPickupItem(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player picker = (Player) e.getEntity();
        Item itemEntity = e.getItem();
        ItemStack stack = itemEntity.getItemStack();
        if (stack == null || !stack.hasItemMeta()) return;
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(ownerKey, PersistentDataType.STRING)) {
            // change owner to the picker so they can use it
            pdc.set(ownerKey, PersistentDataType.STRING, picker.getUniqueId().toString());
            stack.setItemMeta(meta);
            itemEntity.setItemStack(stack);

            // cancel scheduled return for this item
            BukkitTask t = dropReturnTasks.remove(itemEntity.getUniqueId());
            if (t != null) t.cancel();
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player attacker = (Player) e.getDamager();
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon == null || !weapon.hasItemMeta()) return;
        ItemMeta meta = weapon.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        double damage = e.getDamage();

        // Sword sweeping: deal half damage to nearby enemies
        if (pdc.has(swordKey, PersistentDataType.BYTE)) {
            for (Entity ent : e.getEntity().getNearbyEntities(3, 2, 3)) {
                if (ent instanceof LivingEntity && ent != attacker && ent != e.getEntity()) {
                    LivingEntity le = (LivingEntity) ent;
                    le.damage(damage * 0.5, attacker);
                }
            }
        }

        // Axe: disable shields on victims for 3 seconds
        if (pdc.has(axeKey, PersistentDataType.BYTE)) {
            if (e.getEntity() instanceof Player) {
                Player victim = (Player) e.getEntity();
                try {
                    victim.setCooldown(Material.SHIELD, 3 * 20);
                } catch (NoSuchMethodError ex) {
                    // older API: ignore if not available
                }
            }
        }

        // Mace: extra smash damage based on attacker's fall distance
        if (pdc.has(maceKey, PersistentDataType.BYTE)) {
            double fall = attacker.getFallDistance();
            if (fall > 0) {
                double bonus = fall * 1.5; // tunable multiplier
                if (e.getEntity() instanceof LivingEntity) {
                    ((LivingEntity) e.getEntity()).damage(bonus, attacker);
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
            arrow.setDamage(8.0); // tuned
            arrow.setKnockbackStrength(1);
            arrow.setFireTicks(100);
        }
    }

    // Utility: set owners when giving arsenal to a player who already has items set

}

package ru.customhorns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;\nimport org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

public final class CustomHorns extends JavaPlugin implements Listener, TabExecutor {

    /** Типы рогов: id, ванильный звук, название, цвет, кулдаун (сек), описание. */
    public enum Horn {
        PONDER(MusicInstrument.PONDER_GOAT_HORN, "Рог Мудрости", NamedTextColor.AQUA, 25,
                "Исцеляет и даёт поглощение союзникам рядом"),
        SING(MusicInstrument.SING_GOAT_HORN, "Рог Воспевания", NamedTextColor.GOLD, 90,
                "СИЛЬНЕЙШИЙ: мощный боевой бафф всем союзникам,", "а врагов ослабляет и подсвечивает"),
        SEEK(MusicInstrument.SEEK_GOAT_HORN, "Рог Охотника", NamedTextColor.GREEN, 30,
                "Подсвечивает врагов на 40 блоков, ускоряет вас"),
        FEEL(MusicInstrument.FEEL_GOAT_HORN, "Рог Землетрясения", NamedTextColor.DARK_RED, 20,
                "Ударная волна: урон, отбрасывание, замедление"),
        ADMIRE(MusicInstrument.ADMIRE_GOAT_HORN, "Рог Взлёта", NamedTextColor.WHITE, 15,
                "Мощный рывок вперёд и вверх, без урона от падения"),
        CALL(MusicInstrument.CALL_GOAT_HORN, "Рог Зова", NamedTextColor.LIGHT_PURPLE, 90,
                "СИЛЬНЕЙШИЙ: притягивает врагов, подкидывает и бьёт молниями,", "а союзников усиливает"),
        YEARN(MusicInstrument.YEARN_GOAT_HORN, "Рог Тоски", NamedTextColor.DARK_GRAY, 30,
                "Иссушает врагов и лечит вас за каждого"),
        DREAM(MusicInstrument.DREAM_GOAT_HORN, "Рог Грёз", NamedTextColor.BLUE, 20,
                "Телепорт вперёд на 12 блоков + невидимость и скорость");

        final MusicInstrument instrument;
        final String title;
        final TextColor color;
        final int cooldown;
        final String[] lore;

        Horn(MusicInstrument i, String title, TextColor c, int cd, String... lore) {
            this.instrument = i; this.title = title; this.color = c; this.cooldown = cd; this.lore = lore;
        }
    }

    private NamespacedKey key;
    private TeamManager teams;
    private final Map<String, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "horn");
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("horn");
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
        teams = new TeamManager(this);
        PluginCommand tc = getCommand("hteam");
        if (tc != null) { tc.setExecutor(teams); tc.setTabCompleter(teams); }
    }

    @Override
    public void onDisable() {
        if (teams != null) teams.save();
    }

    // ───────────────────────── Выдача ─────────────────────────

    private ItemStack createHorn(Horn h, int amount) {
        ItemStack item = new ItemStack(Material.GOAT_HORN, amount);
        MusicInstrumentMeta meta = (MusicInstrumentMeta) item.getItemMeta();
        meta.setInstrument(h.instrument);
        meta.displayName(Component.text(h.title, h.color)
                .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        for (String line : h.lore) lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Откат: " + h.cooldown + " сек", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, h.name());
        item.setItemMeta(meta);
        return item;
    }

    private Horn readHorn(ItemStack item) {
        if (item == null || item.getType() != Material.GOAT_HORN || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) return null;
        try { return Horn.valueOf(id); } catch (IllegalArgumentException e) { return null; }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("customhorns.admin")) { s.sendMessage(Component.text("Нет прав.", NamedTextColor.RED)); return true; }
        if (a.length == 0) { s.sendMessage(Component.text("/horn give <игрок> <рог> [кол-во] | list | reload", NamedTextColor.YELLOW)); return true; }
        switch (a[0].toLowerCase()) {
            case "list" -> {
                for (Horn h : Horn.values())
                    s.sendMessage(Component.text(h.name().toLowerCase() + " — " + h.title, h.color));
            }
            case "reload" -> {
                reloadConfig();
                s.sendMessage(Component.text("Конфиг перезагружен.", NamedTextColor.GREEN));
            }
            case "give" -> {
                if (a.length < 3) { s.sendMessage(Component.text("/horn give <игрок> <рог> [кол-во]", NamedTextColor.YELLOW)); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED)); return true; }
                Horn h;
                try { h = Horn.valueOf(a[2].toUpperCase()); }
                catch (IllegalArgumentException e) { s.sendMessage(Component.text("Неизвестный рог. /horn list", NamedTextColor.RED)); return true; }
                int n = 1;
                if (a.length > 3) try { n = Math.max(1, Math.min(16, Integer.parseInt(a[3]))); } catch (NumberFormatException ignored) {}
                Map<Integer, ItemStack> left = t.getInventory().addItem(createHorn(h, n));
                left.values().forEach(it -> t.getWorld().dropItemNaturally(t.getLocation(), it));
                s.sendMessage(Component.text("Выдано: " + h.title + " x" + n + " → " + t.getName(), NamedTextColor.GREEN));
            }
            default -> s.sendMessage(Component.text("Неизвестная подкоманда.", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("customhorns.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("give", "list", "reload"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("give")) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        else if (a.length == 3 && a[0].equalsIgnoreCase("give")) for (Horn h : Horn.values()) out.add(h.name().toLowerCase());
        String last = a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }

    // ───────────────────────── Использование ─────────────────────────

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND && e.getHand() != EquipmentSlot.OFF_HAND) return;
        Horn h = readHorn(e.getItem());
        if (h == null) return;

        Player p = e.getPlayer();
        String ck = p.getUniqueId() + ":" + h.name();
        long now = System.currentTimeMillis();
        long until = cooldowns.getOrDefault(ck, 0L);
        if (now < until) {
            e.setCancelled(true); // без звука при откате
            p.sendActionBar(Component.text("Откат: " + ((until - now) / 1000 + 1) + " сек", NamedTextColor.RED));
            return;
        }
        cooldowns.put(ck, now + h.cooldown * 1000L);
        activate(p, h);
    }

    private void activate(Player p, Horn h) {
        switch (h) {
            case PONDER -> {
                for (Player a : allies(p, 10)) {
                    heal(a, 8);
                    effect(a, PotionEffectType.REGENERATION, 20, 1);
                    effect(a, PotionEffectType.ABSORPTION, 30, 2);
                    a.getWorld().spawnParticle(Particle.HEART, a.getLocation().add(0, 1.5, 0), 8, .4, .4, .4);
                }
            }
            case SING -> {
                for (Player a : allies(p, 15)) {
                    effect(a, PotionEffectType.STRENGTH, 45, 1);
                    effect(a, PotionEffectType.SPEED, 45, 1);
                    effect(a, PotionEffectType.REGENERATION, 30, 1);
                    effect(a, PotionEffectType.RESISTANCE, 45, 1);
                    effect(a, PotionEffectType.HASTE, 45, 1);
                    effect(a, PotionEffectType.ABSORPTION, 45, 3);
                    a.getWorld().spawnParticle(Particle.NOTE, a.getLocation().add(0, 2, 0), 15, .6, .5, .6);
                }
                for (LivingEntity en : enemies(p, 15)) {
                    effect(en, PotionEffectType.WEAKNESS, 12, 1);
                    effect(en, PotionEffectType.SLOWNESS, 6, 0);
                    effect(en, PotionEffectType.GLOWING, 12, 0);
                }
                ring(p.getLocation(), 15, Particle.NOTE);
            }
            case SEEK -> {
                effect(p, PotionEffectType.SPEED, 20, 2);
                effect(p, PotionEffectType.NIGHT_VISION, 30, 0);
                for (LivingEntity en : enemies(p, 40)) effect(en, PotionEffectType.GLOWING, 15, 0);
                ring(p.getLocation(), 8, Particle.ELECTRIC_SPARK);
            }
            case FEEL -> {
                for (LivingEntity en : enemies(p, 8)) {
                    en.damage(8, p);
                    Vector away = en.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                    if (away.lengthSquared() < 0.01) away = new Vector(0, 0, 1);
                    en.setVelocity(away.normalize().multiply(1.6).setY(0.9));
                    effect(en, PotionEffectType.SLOWNESS, 4, 2);
                }
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.7f);
                p.getWorld().spawnParticle(Particle.EXPLOSION, p.getLocation(), 6, 2.5, .3, 2.5);
                ring(p.getLocation(), 8, Particle.CLOUD);
            }
            case ADMIRE -> {
                Vector dir = p.getLocation().getDirection().normalize().multiply(1.6);
                p.setVelocity(dir.setY(Math.max(dir.getY(), 0) + 0.9));
                effect(p, PotionEffectType.SLOW_FALLING, 10, 0);
                effect(p, PotionEffectType.JUMP_BOOST, 10, 3);
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 25, .4, .1, .4, .05);
            }
            case CALL -> {
                for (Player a : allies(p, 16)) {
                    effect(a, PotionEffectType.STRENGTH, 45, 1);
                    effect(a, PotionEffectType.RESISTANCE, 45, 1);
                    effect(a, PotionEffectType.SPEED, 30, 1);
                    effect(a, PotionEffectType.ABSORPTION, 45, 3);
                    heal(a, 6);
                }
                for (LivingEntity en : enemies(p, 16)) {
                    Vector pull = p.getLocation().toVector().subtract(en.getLocation().toVector());
                    if (pull.lengthSquared() > 1) pull.normalize();
                    en.setVelocity(pull.multiply(1.4).setY(0.8));
                    en.getWorld().strikeLightningEffect(en.getLocation());
                    en.damage(10, p);
                    effect(en, PotionEffectType.WEAKNESS, 10, 1);
                    effect(en, PotionEffectType.SLOWNESS, 5, 1);
                }
                p.getWorld().spawnParticle(Particle.SONIC_BOOM, p.getLocation().add(0, 1, 0), 1);
                ring(p.getLocation(), 16, Particle.SOUL);
            }
            case YEARN -> {
                int hit = 0;
                for (LivingEntity en : enemies(p, 10)) {
                    en.damage(4, p);
                    effect(en, PotionEffectType.WITHER, 6, 1);
                    effect(en, PotionEffectType.BLINDNESS, 4, 0);
                    en.getWorld().spawnParticle(Particle.SOUL, en.getLocation().add(0, 1, 0), 10, .3, .5, .3, .02);
                    hit++;
                }
                heal(p, Math.min(2 * hit, 12));
                effect(p, PotionEffectType.REGENERATION, 8, 1);
                ring(p.getLocation(), 10, Particle.SOUL);
            }
            case DREAM -> {
                Location from = p.getLocation();
                Location to = from.clone();
                Vector direction = from.getDirection().normalize();
                // Ищем самую дальнюю свободную точку впереди, максимум 12 блоков.
                // Шаг 0.25 блока уменьшает вероятность остановки слишком рано у углов/дверей.
                for (int i = 1; i <= 48; i++) {
                    Location probe = from.clone().add(direction.clone().multiply(i * 0.25));
                    if (!probe.getBlock().isPassable()
                            || !probe.clone().add(0, 1, 0).getBlock().isPassable()) break;
                    to = probe;
                }
                from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 40, .4, .8, .4, .3);
                if (to.distanceSquared(from) > 0.04) {
                    p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    to.getWorld().spawnParticle(Particle.PORTAL, to.clone().add(0, 1, 0), 40, .4, .8, .4, .3);
                } else {
                    p.sendActionBar(Component.text("Перед вами нет свободного места для рывка", NamedTextColor.RED));
                }
                effect(p, PotionEffectType.INVISIBILITY, 5, 0);
                effect(p, PotionEffectType.SPEED, 10, 2);
            }
        }
    }

    // ───────────────────────── Хелперы ─────────────────────────

    /** Союзники: вы сами и участники вашей команды рогов в радиусе. */
    private List<Player> allies(Player caster, double r) {
        List<Player> list = new ArrayList<>();
        list.add(caster);
        for (Entity e : caster.getNearbyEntities(r, r, r))
            if (e instanceof Player pl && pl.getGameMode() != GameMode.SPECTATOR && teams.sameTeam(caster, pl)) list.add(pl);
        return list;
    }

    private List<LivingEntity> enemies(Player caster, double r) {
        List<LivingEntity> list = new ArrayList<>();
        for (Entity e : caster.getNearbyEntities(r, r, r)) {
            if (!(e instanceof LivingEntity le) || e instanceof ArmorStand) continue;
            if (e instanceof Player pl) {
                // Враждебные эффекты действуют и на игроков; союзники команды рогов защищены.
                if (pl.getGameMode() == GameMode.SPECTATOR || teams.sameTeam(caster, pl)) continue;
            }
            if (e instanceof Tameable t && caster.equals(t.getOwner())) continue;
            list.add(le);
        }
        return list;
    }

    private void effect(LivingEntity e, PotionEffectType type, int seconds, int amp) {
        e.addPotionEffect(new PotionEffect(type, seconds * 20, amp, false, true, true));
    }

    private void heal(Player p, double amount) {
        double max = p.getAttribute(Attribute.MAX_HEALTH) != null ? p.getAttribute(Attribute.MAX_HEALTH).getValue() : 20;
        p.setHealth(Math.min(max, p.getHealth() + amount));
    }

    private void ring(Location c, double radius, Particle particle) {
        for (int i = 0; i < 48; i++) {
            double ang = 2 * Math.PI * i / 48;
            c.getWorld().spawnParticle(particle, c.getX() + Math.cos(ang) * radius, c.getY() + 0.3, c.getZ() + Math.sin(ang) * radius, 1, 0, 0, 0, 0);
        }
    }
}

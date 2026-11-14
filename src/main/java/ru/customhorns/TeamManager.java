package ru.customhorns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Команды только для рогов: хранение, приглашения и команда /hteam. */
public final class TeamManager implements TabExecutor {

    private static final class Team {
        String name;
        UUID leader;
        final Set<UUID> members = new LinkedHashSet<>();
    }

    private record Invite(String team, long expires) {}

    private static final Pattern NAME = Pattern.compile("[\\p{L}0-9_]{2,16}");

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Team> teams = new HashMap<>();      // ключ — имя в нижнем регистре
    private final Map<UUID, String> byPlayer = new HashMap<>();   // игрок → ключ команды
    private final Map<UUID, Invite> invites = new HashMap<>();

    public TeamManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "teams.yml");
        load();
    }

    // ───────────── API для рогов ─────────────

    public boolean sameTeam(Player a, Player b) {
        String t = byPlayer.get(a.getUniqueId());
        return t != null && t.equals(byPlayer.get(b.getUniqueId()));
    }

    // ───────────── Хранение ─────────────

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("teams");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            Team t = new Team();
            t.name = s.getString("name", key);
            try { t.leader = UUID.fromString(s.getString("leader", "")); } catch (IllegalArgumentException e) { continue; }
            for (String m : s.getStringList("members")) {
                try { UUID id = UUID.fromString(m); t.members.add(id); byPlayer.put(id, key); } catch (IllegalArgumentException ignored) {}
            }
            if (!t.members.isEmpty()) teams.put(key, t);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, Team> e : teams.entrySet()) {
            String p = "teams." + e.getKey();
            yml.set(p + ".name", e.getValue().name);
            yml.set(p + ".leader", e.getValue().leader.toString());
            yml.set(p + ".members", e.getValue().members.stream().map(UUID::toString).toList());
        }
        try { plugin.getDataFolder().mkdirs(); yml.save(file); }
        catch (IOException ex) { plugin.getLogger().warning("Не удалось сохранить teams.yml: " + ex.getMessage()); }
    }

    // ───────────── Команда /hteam ─────────────

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Только для игроков."); return true; }
        if (a.length == 0) { help(p); return true; }

        String key = byPlayer.get(p.getUniqueId());
        Team team = key == null ? null : teams.get(key);

        switch (a[0].toLowerCase()) {
            case "create" -> {
                if (team != null) { say(p, "Вы уже в команде. Сначала /hteam leave", NamedTextColor.RED); return true; }
                if (a.length < 2 || !NAME.matcher(a[1]).matches()) { say(p, "Название: 2–16 символов (буквы, цифры, _).", NamedTextColor.RED); return true; }
                String k = a[1].toLowerCase();
                if (teams.containsKey(k)) { say(p, "Такое название уже занято.", NamedTextColor.RED); return true; }
                Team t = new Team(); t.name = a[1]; t.leader = p.getUniqueId(); t.members.add(p.getUniqueId());
                teams.put(k, t); byPlayer.put(p.getUniqueId(), k); save();
                say(p, "Команда «" + t.name + "» создана. Приглашайте: /hteam invite <ник>", NamedTextColor.GREEN);
            }
            case "invite" -> {
                if (team == null) { say(p, "Вы не в команде.", NamedTextColor.RED); return true; }
                if (!team.leader.equals(p.getUniqueId())) { say(p, "Приглашать может только лидер.", NamedTextColor.RED); return true; }
                if (a.length < 2) { say(p, "/hteam invite <ник>", NamedTextColor.YELLOW); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { say(p, "Игрок не в сети.", NamedTextColor.RED); return true; }
                if (byPlayer.containsKey(t.getUniqueId())) { say(p, "Этот игрок уже в команде.", NamedTextColor.RED); return true; }
                int max = plugin.getConfig().getInt("team-max-size", 10);
                if (team.members.size() >= max) { say(p, "Команда заполнена (макс. " + max + ").", NamedTextColor.RED); return true; }
                invites.put(t.getUniqueId(), new Invite(byPlayer.get(p.getUniqueId()), System.currentTimeMillis() + 60_000));
                say(p, "Приглашение отправлено: " + t.getName(), NamedTextColor.GREEN);
                t.sendMessage(Component.text(p.getName() + " приглашает вас в команду рогов «" + team.name + "» ", NamedTextColor.GOLD)
                        .append(Component.text("[Принять]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/hteam accept"))));
            }
            case "accept" -> {
                Invite inv = invites.remove(p.getUniqueId());
                if (inv == null || inv.expires() < System.currentTimeMillis()) { say(p, "Нет активных приглашений.", NamedTextColor.RED); return true; }
                if (team != null) { say(p, "Вы уже в команде.", NamedTextColor.RED); return true; }
                Team t = teams.get(inv.team());
                if (t == null) { say(p, "Эта команда больше не существует.", NamedTextColor.RED); return true; }
                if (t.members.size() >= plugin.getConfig().getInt("team-max-size", 10)) { say(p, "Команда заполнена.", NamedTextColor.RED); return true; }
                t.members.add(p.getUniqueId()); byPlayer.put(p.getUniqueId(), inv.team()); save();
                say(p, "Вы вступили в команду «" + t.name + "».", NamedTextColor.GREEN);
                broadcast(t, p.getName() + " вступил в команду.", NamedTextColor.GREEN, p);
            }
            case "leave" -> {
                if (team == null) { say(p, "Вы не в команде.", NamedTextColor.RED); return true; }
                removeMember(team, p.getUniqueId());
                say(p, "Вы покинули команду «" + team.name + "».", NamedTextColor.YELLOW);
            }
            case "kick" -> {
                if (team == null) { say(p, "Вы не в команде.", NamedTextColor.RED); return true; }
                if (!team.leader.equals(p.getUniqueId())) { say(p, "Исключать может только лидер.", NamedTextColor.RED); return true; }
                if (a.length < 2) { say(p, "/hteam kick <ник>", NamedTextColor.YELLOW); return true; }
                UUID target = null;
                for (UUID m : team.members) { String n = Bukkit.getOfflinePlayer(m).getName(); if (n != null && n.equalsIgnoreCase(a[1])) target = m; }
                if (target == null) { say(p, "Такого игрока нет в вашей команде.", NamedTextColor.RED); return true; }
                if (target.equals(p.getUniqueId())) { say(p, "Себя нельзя. Используйте /hteam leave или disband.", NamedTextColor.RED); return true; }
                removeMember(team, target);
                Player tp = Bukkit.getPlayer(target);
                if (tp != null) say(tp, "Вас исключили из команды «" + team.name + "».", NamedTextColor.RED);
                say(p, "Игрок исключён.", NamedTextColor.YELLOW);
            }
            case "disband" -> {
                if (team == null) { say(p, "Вы не в команде.", NamedTextColor.RED); return true; }
                if (!team.leader.equals(p.getUniqueId())) { say(p, "Распустить может только лидер.", NamedTextColor.RED); return true; }
                broadcast(team, "Команда «" + team.name + "» распущена.", NamedTextColor.RED, null);
                disband(team);
            }
            case "list", "info" -> {
                if (team == null) { say(p, "Вы не в команде.", NamedTextColor.RED); return true; }
                say(p, "Команда «" + team.name + "» (" + team.members.size() + "):", NamedTextColor.GOLD);
                for (UUID m : team.members) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                    boolean online = op.isOnline();
                    String n = (op.getName() != null ? op.getName() : m.toString().substring(0, 8))
                            + (m.equals(team.leader) ? " ★" : "");
                    p.sendMessage(Component.text(" • " + n, online ? NamedTextColor.GREEN : NamedTextColor.GRAY));
                }
            }
            default -> help(p);
        }
        return true;
    }

    private void removeMember(Team team, UUID id) {
        team.members.remove(id);
        byPlayer.remove(id);
        if (team.members.isEmpty()) { teams.remove(team.name.toLowerCase()); }
        else if (team.leader.equals(id)) {
            team.leader = team.members.iterator().next();
            broadcast(team, Bukkit.getOfflinePlayer(team.leader).getName() + " теперь лидер команды.", NamedTextColor.GOLD, null);
        }
        save();
    }

    private void disband(Team team) {
        for (UUID m : team.members) byPlayer.remove(m);
        teams.remove(team.name.toLowerCase());
        save();
    }

    private void broadcast(Team t, String text, TextColor color, Player except) {
        for (UUID m : t.members) {
            Player pl = Bukkit.getPlayer(m);
            if (pl != null && !pl.equals(except)) say(pl, text, color);
        }
    }

    private void say(Player p, String text, TextColor color) {
        p.sendMessage(Component.text("[Рога] ", NamedTextColor.GOLD).append(Component.text(text, color)));
    }

    private void help(Player p) {
        say(p, "/hteam create <название> — создать команду", NamedTextColor.YELLOW);
        say(p, "/hteam invite <ник> — пригласить (лидер)", NamedTextColor.YELLOW);
        say(p, "/hteam accept — принять приглашение", NamedTextColor.YELLOW);
        say(p, "/hteam leave — выйти", NamedTextColor.YELLOW);
        say(p, "/hteam kick <ник> — исключить (лидер)", NamedTextColor.YELLOW);
        say(p, "/hteam list — состав команды", NamedTextColor.YELLOW);
        say(p, "/hteam disband — распустить (лидер)", NamedTextColor.YELLOW);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("create", "invite", "accept", "leave", "kick", "list", "disband"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("invite")) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        else if (a.length == 2 && a[0].equalsIgnoreCase("kick") && s instanceof Player p) {
            String k = byPlayer.get(p.getUniqueId());
            Team t = k == null ? null : teams.get(k);
            if (t != null) for (UUID m : t.members) { String n = Bukkit.getOfflinePlayer(m).getName(); if (n != null) out.add(n); }
        }
        String last = a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }
}

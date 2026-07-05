package com.liverecord.farmcountdown;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * s2e-farm-pro のゲーム開始カウントダウン秒数（既定10秒・ハードコード）を、
 * 実行中のカウントダウン(BukkitRunnable)へのリフレクションで上書きする実験的プラグイン。
 *
 * farm-pro は難読化されており、カウントダウン秒数は設定ファイルにもコマンドにも存在せず
 * ハードコード＋毎ゲーム生成のため、外部から変えるにはこの方式しかない。
 * どの int フィールドが残り秒数かは debug ログで特定してから enabled にする。
 */
public final class FarmCountdownPlugin extends JavaPlugin implements TabExecutor {

    private static final int MIN = 1;
    private static final int MAX = 3600;

    private String sourcePlugin;
    private boolean enabled;
    private int seconds;
    private int fieldIndex;
    private String tickerClass;
    private int minIntFields;
    private int maxIntFields;
    private int pollInterval;
    private boolean debug;
    private int maxDebugLogs;

    private int taskId = -1;

    // ticker インスタンスごとに前回観測した残り秒数（リセット検知用）。
    private final Map<Object, Integer> lastValue = new WeakHashMap<>();
    // ticker インスタンスごとに「最後に適用した目標秒数」（目標変更を検知して再適用するため）。
    private final Map<Object, Integer> appliedTarget = new WeakHashMap<>();
    private final Map<Object, Integer> debugCount = new WeakHashMap<>();
    private final Map<Object, String> lastLogged = new WeakHashMap<>();
    // ticker が参照する「マネージャ」オブジェクト（カウントダウン既定値の出所）。
    // ここの int を常時 seconds に保つと、次ラウンドの ticker は最初から seconds で生成される＝開始時の一瞬10が消える。
    private java.lang.ref.WeakReference<Object> cachedManager = new java.lang.ref.WeakReference<>(null);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getResource("README.md") != null) {
            saveResource("README.md", true);
        }
        loadSettings();
        getCommand("farmcountdown").setExecutor(this);
        startTask();
        getLogger().info("FarmCountdown 有効化。enabled=" + enabled + " seconds=" + seconds
                + " field-index=" + fieldIndex + " debug=" + debug
                + "（対象 " + sourcePlugin + "）");
        getLogger().info("※ farm-pro の難読化内部を操作します。必ず配信外でテストし、正しい field を debug で特定してください。");
    }

    @Override
    public void onDisable() {
        stopTask();
    }

    private void loadSettings() {
        reloadConfig();
        sourcePlugin = getConfig().getString("source-plugin", "s2e-farm-pro");
        enabled = getConfig().getBoolean("enabled", false);
        seconds = clamp(getConfig().getInt("seconds", 10));
        fieldIndex = Math.max(0, getConfig().getInt("remaining-field-index", 1));
        tickerClass = getConfig().getString("ticker-class", "").trim();
        minIntFields = Math.max(1, getConfig().getInt("min-int-fields", 2));
        maxIntFields = Math.max(minIntFields, getConfig().getInt("max-int-fields", 2));
        pollInterval = Math.max(1, getConfig().getInt("poll-interval-ticks", 1));
        debug = getConfig().getBoolean("debug", true);
        maxDebugLogs = Math.max(0, getConfig().getInt("max-debug-logs", 30));
    }

    private int clamp(int v) {
        return Math.max(MIN, Math.min(MAX, v));
    }

    private void startTask() {
        stopTask();
        taskId = Bukkit.getScheduler().runTaskTimer(this, this::tick, pollInterval, pollInterval).getTaskId();
    }

    private void stopTask() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    // ===================== ポーリング本体 =====================

    private void tick() {
        // 出所(manager)が既に分かっていたか（=新ラウンドが既に正しい値で生成される状態か）。
        boolean hadManager = cachedManager.get() != null;

        // ラウンド間でも、キャッシュ済みマネージャの出所int を seconds に保つ
        // → 次の ticker は最初から seconds で生成され、開始/リセット時の一瞬10が出ない。
        if (enabled && seconds > 0) {
            Object m = cachedManager.get();
            if (m != null) {
                setManagerSource(m, seconds);
            }
        }

        Object ticker = findTicker();
        if (ticker == null) {
            return;
        }
        if (debug) {
            logTicker(ticker);
        }
        if (!enabled || seconds <= 0) {
            return;
        }
        // ticker から「出所マネージャ」を取得・キャッシュし、その出所int も seconds にする。
        Object mgr = managerOf(ticker);
        if (mgr != null) {
            cachedManager = new java.lang.ref.WeakReference<>(mgr);
            setManagerSource(mgr, seconds);
        }
        List<Field> fs = intFields(ticker.getClass());
        if (fieldIndex < 0 || fieldIndex >= fs.size()) {
            return;
        }
        Integer cur = readInt(fs.get(fieldIndex), ticker);
        if (cur == null) {
            return;
        }
        Integer prev = lastValue.get(ticker);
        Integer applied = appliedTarget.get(ticker);
        boolean newInstance = (prev == null);

        if (newInstance) {
            // 新しいカウントダウン。出所(manager)を既に設定済みなら、この ticker は最初から
            // seconds で生成されているので「実行中の上書きはしない」（=戻し上書きによる二重表示を防ぐ）。
            // 出所が未設定だった最初の1回だけ、実行中も補正する（起動直後のラウンド用）。
            if (!hadManager && cur.intValue() != seconds) {
                setIntFieldByIndex(ticker, fieldIndex, seconds);
                getLogger().info("[farmcountdown] 初回ラウンド補正（" + cur + "→" + seconds + "秒）。");
            }
            appliedTarget.put(ticker, seconds);
            lastValue.put(ticker, cur);
        } else if (applied != null && applied.intValue() != seconds) {
            // カウントダウン中に /fcd で目標秒数を変えた → 実行中のカウントへ即反映（1回だけジャンプ）。
            if (cur.intValue() != seconds) {
                setIntFieldByIndex(ticker, fieldIndex, seconds);
                getLogger().info("[farmcountdown] カウント中に変更（" + cur + "→" + seconds + "秒）。");
            }
            appliedTarget.put(ticker, seconds);
            lastValue.put(ticker, cur);
        } else {
            // 通常カウントダウン中はそのまま減らす（上書きしない）。
            lastValue.put(ticker, cur);
        }
    }

    private Integer readInt(Field f, Object target) {
        try {
            f.setAccessible(true);
            return f.getInt(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * ticker が保持する「マネージャ」オブジェクトを返す。
     * ＝ ticker の非プリミティブ参照のうち、クラスが int をちょうど1つだけ持つもの。
     * （その int がカウントダウンの既定値の出所。ticker は生成時にここからコピーする。）
     */
    private Object managerOf(Object ticker) {
        for (Field f : ticker.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object v = f.get(ticker);
                if (v != null && singleIntField(v.getClass()) != null) {
                    return v;
                }
            } catch (Throwable ignore) {
                // アクセス不可は無視
            }
        }
        return null;
    }

    /** クラスが宣言する int フィールドがちょうど1つならそれを返す。曖昧（0個/2個以上）なら null。 */
    private Field singleIntField(Class<?> c) {
        Field found = null;
        int n = 0;
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                found = f;
                n++;
            }
        }
        return n == 1 ? found : null;
    }

    /** マネージャの唯一の int（カウントダウン既定値）を value にする。 */
    private void setManagerSource(Object manager, int value) {
        Field f = singleIntField(manager.getClass());
        if (f == null) {
            return;
        }
        try {
            f.setAccessible(true);
            if (f.getInt(manager) != value) {
                f.setInt(manager, value);
            }
        } catch (Throwable ignore) {
            // 失敗は無視（出所書換えは任意の最適化。実行中ticker側の上書きで担保される）
        }
    }

    /** source-plugin が所有するタスクの中から、カウントダウン ticker（int を複数持つ BukkitRunnable）を探す。 */
    private Object findTicker() {
        Plugin owner = getServer().getPluginManager().getPlugin(sourcePlugin);
        for (BukkitTask t : Bukkit.getScheduler().getPendingTasks()) {
            if (owner != null && t.getOwner() != owner) {
                continue;
            }
            Object found = bfsFindTicker(t, 3);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean isTicker(Object o) {
        if (!(o instanceof BukkitRunnable)) {
            return false;
        }
        Class<?> c = o.getClass();
        if (!tickerClass.isEmpty() && !c.getName().equals(tickerClass)) {
            return false;
        }
        int n = intFields(c).size();
        return n >= minIntFields && n <= maxIntFields;
    }

    /** タスクが内部に保持する Runnable を幅優先で辿り、ticker を探す。 */
    private Object bfsFindTicker(Object root, int maxDepth) {
        if (root == null) {
            return null;
        }
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{root, 0});
        seen.add(root);
        while (!queue.isEmpty()) {
            Object[] cur = queue.poll();
            Object obj = cur[0];
            int depth = (Integer) cur[1];
            if (obj == null) {
                continue;
            }
            if (isTicker(obj)) {
                return obj;
            }
            if (depth >= maxDepth) {
                continue;
            }
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                        continue;
                    }
                    if ("next".equals(f.getName())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v != null && !seen.contains(v)) {
                            seen.add(v);
                            queue.add(new Object[]{v, depth + 1});
                        }
                    } catch (Throwable ignore) {
                        // アクセス不可は無視
                    }
                }
            }
        }
        return null;
    }

    /** ticker クラスが宣言する int フィールドを宣言順で返す。 */
    private List<Field> intFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) {
                out.add(f);
            }
        }
        return out;
    }

    private boolean setIntFieldByIndex(Object ticker, int index, int value) {
        List<Field> fs = intFields(ticker.getClass());
        if (index < 0 || index >= fs.size()) {
            return false;
        }
        try {
            Field f = fs.get(index);
            f.setAccessible(true);
            f.setInt(ticker, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private int[] readIntFields(Object ticker) {
        List<Field> fs = intFields(ticker.getClass());
        int[] vals = new int[fs.size()];
        for (int i = 0; i < fs.size(); i++) {
            try {
                fs.get(i).setAccessible(true);
                vals[i] = fs.get(i).getInt(ticker);
            } catch (Throwable t) {
                vals[i] = Integer.MIN_VALUE;
            }
        }
        return vals;
    }

    private void logTicker(Object ticker) {
        int count = debugCount.getOrDefault(ticker, 0);
        if (count >= maxDebugLogs) {
            return;
        }
        int[] vals = readIntFields(ticker);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.length; i++) {
            sb.append("field[").append(i).append("]=").append(vals[i]).append(' ');
        }
        String line = sb.toString().trim();
        if (line.equals(lastLogged.get(ticker))) {
            return; // 値が変わったときだけ出す
        }
        lastLogged.put(ticker, line);
        debugCount.put(ticker, count + 1);
        String cn = ticker.getClass().getName();
        getLogger().info("[debug] ticker cls#" + Integer.toHexString(cn.hashCode())
                + " ints=" + intFields(ticker.getClass()).size()
                + " (" + ticker.hashCode() + ") " + line);
        dumpClassName(cn);
    }

    // 検出したクラスのフル難読化名を data フォルダへ1度だけ記録（精密指定したい時の参照用）。
    private final Set<String> dumpedClasses = new java.util.HashSet<>();
    private void dumpClassName(String name) {
        if (!dumpedClasses.add(name)) {
            return;
        }
        try {
            java.io.File f = new java.io.File(getDataFolder(), "detected-tickers.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write("cls#" + Integer.toHexString(name.hashCode()) + " = " + name + "\n");
            }
        } catch (Exception ignore) {
            // 失敗は無視
        }
    }

    // ===================== コマンド =====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("farmcountdown")) {
            return false;
        }
        if (!sender.hasPermission("farmcountdown.use")) {
            sender.sendMessage("§cこのコマンドを使う権限がありません。");
            return true;
        }
        // 引数なし or status: 状態表示
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§e=== FarmCountdown ===");
            sender.sendMessage("§7上書き: " + (enabled ? "§aON" : "§eOFF") + " §7/ 秒数: §f" + seconds
                    + (getServer().getPluginManager().getPlugin(sourcePlugin) == null ? " §c(" + sourcePlugin + "未導入)" : ""));
            Object ticker = findTicker();
            if (ticker != null) {
                int[] vals = readIntFields(ticker);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < vals.length; i++) {
                    sb.append("field[").append(i).append("]=").append(vals[i]).append("  ");
                }
                sender.sendMessage("§a検出中: §f" + sb.toString().trim());
            }
            sender.sendMessage("§7使い方: /farmcountdown <秒> | off | status | reload");
            return true;
        }
        String a = args[0].toLowerCase(Locale.ROOT);

        if (a.equals("off")) {
            enabled = false;
            getConfig().set("enabled", false);
            saveConfig();
            sender.sendMessage("§eカウントダウンの上書きを OFF にしました。");
            return true;
        }
        if (a.equals("reload")) {
            loadSettings();
            startTask();
            sender.sendMessage("§aconfig を再読み込みしました。（秒数 " + seconds + " / "
                    + (enabled ? "ON" : "OFF") + "）");
            return true;
        }
        // 数値 = その秒数に設定して ON（カウントダウン中なら即反映）
        try {
            int v = Integer.parseInt(a);
            if (v < MIN || v > MAX) {
                sender.sendMessage("§c" + MIN + "〜" + MAX + " で指定してください。");
                return true;
            }
            seconds = v;
            enabled = true;
            getConfig().set("seconds", seconds);
            getConfig().set("enabled", true);
            saveConfig();
            sender.sendMessage("§aカウントダウンを §f" + seconds + " 秒§a にしました（実行中ならすぐ反映）。");
        } catch (NumberFormatException e) {
            sender.sendMessage("§e使い方: /farmcountdown <秒> | off | status | reload  （例: /farmcountdown 5）");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            for (String s : new String[]{"5", "10", "15", "20", "30", "off", "status", "reload"}) {
                if (s.startsWith(p)) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}

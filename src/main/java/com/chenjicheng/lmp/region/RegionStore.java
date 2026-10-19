package com.chenjicheng.lmp.region;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import com.chenjicheng.lmp.LmpMod;

import org.jetbrains.annotations.Nullable;

/**
 * 服务端权威的材料区存储：每个存档一份 regions.json。
 *
 * <p>写入使用「临时文件 + 原子替换」，避免崩溃时留下半个文件。区域版本号 revision
 * 是单调递增的计数器：任何变更（新建/改框/删除/绑定 placement）以及服务器每次启动都会推进，
 * 客户端据此判断自己的快照是否过期——重启后必须让客户端重新取一次，否则会渲染旧数据。
 */
public final class RegionStore
{
    private static final int FORMAT_VERSION = 1;
    private static final String FILE_NAME = "regions.json";
    private static final String DIR_NAME = "lmp";

    private final Path file;
    private final Map<UUID, MaterialRegion> regions = new LinkedHashMap<>();
    private long revisionCounter;
    private boolean lastSaveSucceeded = true;

    public RegionStore(Path file)
    {
        this.file = file;
    }

    /** 存档根目录下的 lmp/regions.json。 */
    public static Path fileFor(Path worldRoot)
    {
        return worldRoot.resolve(DIR_NAME).resolve(FILE_NAME);
    }

    public static RegionStore load(Path file)
    {
        RegionStore store = new RegionStore(file);
        store.read();
        return store;
    }

    public Collection<MaterialRegion> all()
    {
        return List.copyOf(this.regions.values());
    }

    public Optional<MaterialRegion> byId(UUID id)
    {
        return Optional.ofNullable(this.regions.get(id));
    }

    public Optional<MaterialRegion> byPlacement(UUID placementId)
    {
        return this.regions.values().stream()
                .filter(region -> placementId.equals(region.placementId()))
                .findFirst();
    }

    public List<MaterialRegion> inDimension(Identifier dimension)
    {
        return this.regions.values().stream()
                .filter(region -> region.dimension().equals(dimension))
                .toList();
    }

    public long revisionCounter()
    {
        return this.revisionCounter;
    }

    /** 新增或替换一个区域；分配新的版本号并落盘。 */
    public MaterialRegion put(MaterialRegion region)
    {
        MaterialRegion stored = region.withRevision(++this.revisionCounter);
        this.regions.put(stored.id(), stored);
        this.save();
        return stored;
    }

    /**
     * 把一次扫描的结果写回内存里的区域，<b>不</b>落盘。
     *
     * <p>给扫描收尾用：一次扫描要改的只有 {@code lastScanAt} 与 {@code coveragePermille}，
     * 这两个纯属界面展示信息，不值得在收尾那一 tick 上做一次完整的文件写入
     * （实测这一次写盘把该 tick 顶到 19.5ms，接近半个 tick 预算）。真正的持久化由
     * 区域增删改（{@link #put} / {@link #remove}）与服务器停止时的 {@link #save()} 负责。
     *
     * <p><b>为什么参数是三个字段而不是一整条区域</b>：早先这里接受一条完整的
     * {@link MaterialRegion} 并整体替换，而调用方传的是**扫描开始时**捕获的那份。扫描按 2ms/tick
     * 分片、可以跨很多 tick，期间玩家改名、改范围或绑定 placement 都会被这份旧记录悄悄覆盖回去，
     * 并在服务器停止时落盘——用户编辑就这么丢了。现在参数里根本没有旧记录可传，
     * 这条路径不可能再回滚别的字段。
     *
     * <p>契约：只更新<b>已存在</b>的区域，不新增。区域在扫描期间被删掉时返回空，调用方据此丢弃
     * 这一轮结果——避免把刚删掉的区域又写回内存。
     *
     * @return 更新后的区域；区域不存在时为空
     */
    public java.util.Optional<MaterialRegion> applyScanResult(UUID id, long scanTime, int coveragePermille)
    {
        MaterialRegion current = this.regions.get(id);

        if (current == null)
        {
            return java.util.Optional.empty();
        }

        MaterialRegion stored = current.withScanResult(scanTime, ++this.revisionCounter, coveragePermille);
        this.regions.put(id, stored);
        return java.util.Optional.of(stored);
    }

    public boolean remove(UUID id)
    {
        if (this.regions.remove(id) != null)
        {
            this.revisionCounter++;
            this.save();
            return true;
        }

        return false;
    }

    /**
     * 服务器启动时调用：把所有区域版本号推进一格并落盘。
     * 这样重启后客户端不会误以为自己的缓存仍然有效。
     */
    public void onServerStart()
    {
        if (this.regions.isEmpty())
        {
            return;
        }

        long next = ++this.revisionCounter;
        this.regions.replaceAll((id, region) -> region.withRevision(next));
        this.save();
    }

    // ---------------------------------------------------------------- 持久化

    private void read()
    {
        if (!Files.isRegularFile(this.file))
        {
            return;
        }

        try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8))
        {
            JsonElement root = JsonParser.parseReader(reader);

            if (!root.isJsonObject())
            {
                LmpMod.LOGGER.warn("材料区文件格式不正确，已忽略：{}", this.file);
                return;
            }

            JsonObject obj = root.getAsJsonObject();
            long version = getLong(obj, "version", FORMAT_VERSION);

            if (version != FORMAT_VERSION)
            {
                // 不认识的版本按「尽力读取」处理：字段缺失会各自走默认值，读不出来的记录会被跳过。
                // 告警是为了让升级路径上的问题在日志里可见，而不是变成一堆空区域。
                LmpMod.LOGGER.warn("材料区文件的版本是 {}，本模组按版本 {} 读取：{}", version, FORMAT_VERSION, this.file);
            }

            this.revisionCounter = getLong(obj, "revisionCounter", 0L);
            JsonArray array = obj.getAsJsonArray("regions");

            if (array == null)
            {
                return;
            }

            for (JsonElement element : array)
            {
                MaterialRegion region = readRegion(element);

                if (region != null)
                {
                    // 语义非法的记录（体积/区块数超限、名字为空）**保留**但告警：丢弃等于静默删掉
                    // 玩家亲手建的区域，而保留下来玩家还能在设置界面里把范围改回来。
                    // 扫描器会跳过这类区域，见 ScanScheduler.startScan。
                    region.validate().ifPresent(invalid -> LmpMod.LOGGER.warn(
                            "材料区「{}」的当前定义不合法（{}），已保留但不会被扫描，请在界面里重新划定范围",
                            region.name(), invalid.key()));

                    this.regions.put(region.id(), region);
                }
            }
        }
        catch (Exception e)
        {
            LmpMod.LOGGER.error("读取材料区文件失败：{}", this.file, e);
        }
    }

    private static @Nullable MaterialRegion readRegion(JsonElement element)
    {
        if (!element.isJsonObject())
        {
            return null;
        }

        try
        {
            JsonObject obj = element.getAsJsonObject();
            UUID id = UUID.fromString(obj.get("id").getAsString());
            String name = obj.get("name").getAsString();

            // 超长名字不该让整份文件读不出来，但也不能原样留着：区域清单要用 writeString 广播，
            // 超过 32767 字节会抛异常，一条坏记录能把所有人的区域清单打断
            if (name.length() > MaterialRegion.MAX_NAME_LENGTH)
            {
                LmpMod.LOGGER.warn("材料区名字过长（{} 个字符），已截断到 {}：{}",
                        name.length(), MaterialRegion.MAX_NAME_LENGTH, obj.get("id").getAsString());
                name = name.substring(0, MaterialRegion.MAX_NAME_LENGTH);
            }
            Identifier dimension = Identifier.of(obj.get("dimension").getAsString());
            BlockPos pos1 = readPos(obj.getAsJsonArray("pos1"));
            BlockPos pos2 = readPos(obj.getAsJsonArray("pos2"));
            UUID placementId = readUuid(obj, "placementId");
            UUID owner = readUuid(obj, "owner");

            return new MaterialRegion(
                    id, name, dimension, pos1, pos2, placementId, owner,
                    getLong(obj, "createdAt", 0L),
                    getLong(obj, "lastScanAt", 0L),
                    getLong(obj, "revision", 0L),
                    (int) getLong(obj, "coveragePermille", 0L));
        }
        catch (Exception e)
        {
            LmpMod.LOGGER.warn("跳过一条无法解析的材料区记录：{}", element, e);
            return null;
        }
    }

    private static @Nullable UUID readUuid(JsonObject obj, String key)
    {
        JsonElement element = obj.get(key);
        return element == null || element.isJsonNull() ? null : UUID.fromString(element.getAsString());
    }

    private static BlockPos readPos(JsonArray array)
    {
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    /**
     * 读一个数字字段。
     *
     * <p>必须容错：这里读的是磁盘上的 JSON，任何一处类型写错（例如 {@code version} 被写成字符串）
     * 都会让整个 {@link #read()} 抛出并被外层吞掉——结果是内存里一个区域都没有，随后一次保存
     * 就把原文件覆盖掉，用户手工改坏一个字段等于丢掉全部材料区。单个字段读不出来时退回默认值。
     */
    private static long getLong(JsonObject obj, String key, long fallback)
    {
        JsonElement element = obj.get(key);

        if (element == null || element.isJsonNull())
        {
            return fallback;
        }

        try
        {
            return element.getAsLong();
        }
        catch (RuntimeException e)
        {
            LmpMod.LOGGER.warn("材料区文件里的字段 {} 不是数字（{}），按默认值 {} 处理", key, element, fallback);
            return fallback;
        }
    }

    /**
     * 把当前内容原子地写到磁盘。
     *
     * @return 是否写成功；失败时已经记了服务端日志，调用方据此决定要不要提示玩家
     */
    public boolean save()
    {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("revisionCounter", this.revisionCounter);
        JsonArray array = new JsonArray();

        for (MaterialRegion region : this.regions.values())
        {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", region.id().toString());
            obj.addProperty("name", region.name());
            obj.addProperty("dimension", region.dimension().toString());
            obj.add("pos1", writePos(region.pos1()));
            obj.add("pos2", writePos(region.pos2()));
            obj.addProperty("placementId", region.placementId() == null ? null : region.placementId().toString());
            obj.addProperty("owner", region.owner() == null ? null : region.owner().toString());
            obj.addProperty("createdAt", region.createdAt());
            obj.addProperty("lastScanAt", region.lastScanAt());
            obj.addProperty("revision", region.revision());
            obj.addProperty("coveragePermille", region.coveragePermille());
            array.add(obj);
        }

        root.add("regions", array);

        try
        {
            Path parent = this.file.getParent();

            if (parent != null)
            {
                Files.createDirectories(parent);
            }

            Path tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");

            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))
            {
                writer.write(root.toString());
            }

            try
            {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException atomicMoveUnsupported)
            {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }

            this.lastSaveSucceeded = true;
        }
        catch (IOException e)
        {
            this.lastSaveSucceeded = false;
            LmpMod.LOGGER.error("写入材料区文件失败：{}（改动仍在内存里，但重启后会丢失）", this.file, e);
        }

        return this.lastSaveSucceeded;
    }

    /** 最近一次 {@link #save()} 是否成功。 */
    public boolean lastSaveSucceeded()
    {
        return this.lastSaveSucceeded;
    }

    private static JsonArray writePos(BlockPos pos)
    {
        JsonArray array = new JsonArray(3);
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    /** 仅用于测试与诊断。 */
    public List<UUID> ids()
    {
        return new ArrayList<>(this.regions.keySet());
    }
}

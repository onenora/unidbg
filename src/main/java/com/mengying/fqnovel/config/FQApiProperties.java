package com.mengying.fqnovel.config;

import com.mengying.fqnovel.utils.Texts;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * FQ API 配置属性
 * 用于管理FQ API的设备参数和请求配置
 */
@Component
@ConfigurationProperties(prefix = "fq.api")
public class FQApiProperties {
    private static final String DEVICE_CONFIG_PREFIX = "fq.api.device";
    private static final String DEVICE_POOL_PREFIX = "fq.api.device-pool";
    
    /**
     * API基础URL
     */
    private String baseUrl;
    
    /**
     * 运行时 User-Agent；未显式配置时可从设备池启动基线继承。
     */
    private String userAgent;
    
    /**
     * 运行时 Cookie；未显式配置时可从设备池启动基线继承。
     */
    private String cookie;
    
    /**
     * 运行时设备参数；未显式配置时可从设备池启动基线继承。
     */
    private Device device = new Device();

    /**
     * 预置设备池：风控（ILLEGAL_ACCESS 等）时按配置切换设备信息。
     * <p>
     * 配置示例：
     * <pre>
     * fq:
     *   api:
     *     device-pool:
     *       - name: dev1
     *         user-agent: ...
     *         cookie: ...
     *         device: { ... }
     *       - name: dev2
     *         ...
     * </pre>
     */
    private List<DeviceProfile> devicePool = new ArrayList<>();

    /**
     * 设备池生效数量（默认 3）：仅使用前 N 个设备，避免配置过大/频繁轮换。
     */
    private int devicePoolSize = 3;

    /**
     * 启动时是否随机选择设备池中的一个作为当前设备（默认 true）。
     */
    private boolean devicePoolShuffleOnStartup = true;

    /**
     * 启动时优先选择指定 name 的设备（可选）。
     * <p>
     * 配置后将忽略 {@link #devicePoolShuffleOnStartup} 的随机选择逻辑，
     * 始终使用该 name 对应的设备作为启动设备（未找到则回退到原有逻辑）。
     * <pre>
     * fq:
     *   api:
     *     device-pool-startup-name: dev001
     * </pre>
     */
    private String devicePoolStartupName;

    /**
     * 启动时是否对“随机选中的设备”做一次轻量探测（默认 false）。
     * <p>
     * 用于规避某些设备一上来就触发风控/空响应（例如 SEARCH_NO_SEARCH_ID）。
     * 探测失败则会继续尝试设备池内的下一个设备，直到成功或耗尽尝试次数。
     */
    private boolean devicePoolProbeOnStartup = false;

    /**
     * 启动探测最大尝试次数（默认 3）。
     * 实际尝试次数不会超过 devicePoolSize 和 devicePool 实际数量。
     */
    private int devicePoolProbeMaxAttempts = 3;

    /**
     * 风控切换冷却时间（毫秒，默认 30000）。
     * 避免触发风控后频繁轮换设备导致“疯狂切换/疯狂刷新 registerkey”。
     */
    private long deviceRotateCooldownMs = 30_000L;

    /**
     * registerkey 缓存最大条数（默认 32）。
     * <p>
     * keyver 可能随时间变化；无界缓存会导致长期运行内存增长。
     */
    private int registerKeyCacheMaxEntries = 32;

    /**
     * registerkey 缓存 TTL（ms，默认 60 分钟）。
     * <p>
     * 避免长时间持有过期 keyver；下次请求会自动刷新。
     */
    private long registerKeyCacheTtlMs = 60 * 60 * 1000L;

    /**
     * 运行时快照：请求链路统一从该快照读取，保证 userAgent/cookie/device 一致性。
     */
    private volatile RuntimeProfile runtimeProfile;

    @PostConstruct
    public synchronized void initRuntimeProfile() {
        inheritRuntimeDefaultsFromDevicePool();
        refreshRuntimeProfileLocked();
        validateRuntimeConfiguration();
    }

    public RuntimeProfile getRuntimeProfile() {
        RuntimeProfile snapshot = runtimeProfile;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (runtimeProfile == null) {
                inheritRuntimeDefaultsFromDevicePool();
                refreshRuntimeProfileLocked();
            }
            return runtimeProfile;
        }
    }

    public String getUserAgent() {
        return getRuntimeProfile().getUserAgent();
    }

    public String getCookie() {
        return getRuntimeProfile().getCookie();
    }

    public synchronized void setUserAgent(String userAgent) {
        applyNormalizedIfPresent(userAgent, value -> this.userAgent = value);
        refreshRuntimeProfileLocked();
    }

    public synchronized void setCookie(String cookie) {
        applyNormalizedIfPresent(cookie, value -> this.cookie = value);
        refreshRuntimeProfileLocked();
    }

    public synchronized void setDevice(Device device) {
        if (device != null) {
            Device normalizedDevice = copyDevice(device);
            validateRequiredDevice(normalizedDevice, DEVICE_CONFIG_PREFIX);
            this.device = normalizedDevice;
        }
        refreshRuntimeProfileLocked();
    }

    /**
     * 原子切换运行时设备信息。
     */
    public synchronized void applyRuntimeProfile(String userAgent, String cookie, Device device) {
        applyNormalizedIfPresent(userAgent, value -> this.userAgent = value);
        applyNormalizedIfPresent(cookie, value -> this.cookie = value);

        Device runtimeDevice = copyDevice(deviceOrCurrent(device));
        validateRequiredDevice(runtimeDevice, DEVICE_CONFIG_PREFIX);
        if (device != null) {
            this.device = runtimeDevice;
        }

        refreshRuntimeProfileLocked();
    }

    private void refreshRuntimeProfileLocked() {
        this.runtimeProfile = RuntimeProfile.of(this.userAgent, this.cookie, this.device);
    }

    private void inheritRuntimeDefaultsFromDevicePool() {
        DeviceProfile bootstrapProfile = resolveBootstrapProfile();
        if (bootstrapProfile == null) {
            return;
        }

        this.userAgent = Texts.defaultIfBlank(
            normalizeNullable(this.userAgent),
            normalizeNullable(bootstrapProfile.getUserAgent())
        );
        this.cookie = Texts.defaultIfBlank(
            normalizeNullable(this.cookie),
            normalizeNullable(bootstrapProfile.getCookie())
        );
        this.device = mergeDevice(this.device, bootstrapProfile.getDevice());
    }

    private static String normalizeNullable(String value) {
        return Texts.trimToNull(value);
    }

    private static void applyNormalizedIfPresent(String value, Consumer<String> setter) {
        setIfPresent(normalizeNullable(value), setter);
    }

    private Device deviceOrCurrent(Device preferred) {
        return preferred == null ? this.device : preferred;
    }

    private static void setIfPresent(String value, Consumer<String> setter) {
        if (value == null || setter == null) {
            return;
        }
        setter.accept(value);
    }

    private static Device copyDevice(Device source) {
        Device target = new Device();
        if (source == null) {
            return target;
        }

        copyIdentityFields(source, target);
        copyVersionFields(source, target);
        copyHardwareFields(source, target);
        copySystemFields(source, target);
        return target;
    }

    private static Device mergeDevice(Device primary, Device fallback) {
        Device target = new Device();
        mergeIdentityFields(primary, fallback, target);
        mergeVersionFields(primary, fallback, target);
        mergeHardwareFields(primary, fallback, target);
        mergeSystemFields(primary, fallback, target);
        return target;
    }

    private static void copyIdentityFields(Device source, Device target) {
        copyIfText(source.getCdid(), target::setCdid);
        copyIfText(source.getInstallId(), target::setInstallId);
        copyIfText(source.getDeviceId(), target::setDeviceId);
        copyIfText(source.getAid(), target::setAid);
    }

    private static void mergeIdentityFields(Device primary, Device fallback, Device target) {
        mergeIfText(primary == null ? null : primary.getCdid(), fallback == null ? null : fallback.getCdid(), target::setCdid);
        mergeIfText(primary == null ? null : primary.getInstallId(), fallback == null ? null : fallback.getInstallId(), target::setInstallId);
        mergeIfText(primary == null ? null : primary.getDeviceId(), fallback == null ? null : fallback.getDeviceId(), target::setDeviceId);
        mergeIfText(primary == null ? null : primary.getAid(), fallback == null ? null : fallback.getAid(), target::setAid);
    }

    private static void copyVersionFields(Device source, Device target) {
        copyIfText(source.getVersionCode(), target::setVersionCode);
        copyIfText(source.getVersionName(), target::setVersionName);
        copyIfText(source.getUpdateVersionCode(), target::setUpdateVersionCode);
    }

    private static void mergeVersionFields(Device primary, Device fallback, Device target) {
        mergeIfText(primary == null ? null : primary.getVersionCode(), fallback == null ? null : fallback.getVersionCode(), target::setVersionCode);
        mergeIfText(primary == null ? null : primary.getVersionName(), fallback == null ? null : fallback.getVersionName(), target::setVersionName);
        mergeIfText(primary == null ? null : primary.getUpdateVersionCode(), fallback == null ? null : fallback.getUpdateVersionCode(), target::setUpdateVersionCode);
    }

    private static void copyHardwareFields(Device source, Device target) {
        copyIfText(source.getDeviceType(), target::setDeviceType);
        copyIfText(source.getDeviceBrand(), target::setDeviceBrand);
        copyIfText(source.getResolution(), target::setResolution);
        copyIfText(source.getDpi(), target::setDpi);
        copyIfText(source.getHostAbi(), target::setHostAbi);
    }

    private static void mergeHardwareFields(Device primary, Device fallback, Device target) {
        mergeIfText(primary == null ? null : primary.getDeviceType(), fallback == null ? null : fallback.getDeviceType(), target::setDeviceType);
        mergeIfText(primary == null ? null : primary.getDeviceBrand(), fallback == null ? null : fallback.getDeviceBrand(), target::setDeviceBrand);
        mergeIfText(primary == null ? null : primary.getResolution(), fallback == null ? null : fallback.getResolution(), target::setResolution);
        mergeIfText(primary == null ? null : primary.getDpi(), fallback == null ? null : fallback.getDpi(), target::setDpi);
        mergeIfText(primary == null ? null : primary.getHostAbi(), fallback == null ? null : fallback.getHostAbi(), target::setHostAbi);
    }

    private static void copySystemFields(Device source, Device target) {
        copyIfText(source.getRomVersion(), target::setRomVersion);
        copyIfText(source.getOsVersion(), target::setOsVersion);
        copyIfText(source.getOsApi(), target::setOsApi);
    }

    private static void mergeSystemFields(Device primary, Device fallback, Device target) {
        mergeIfText(primary == null ? null : primary.getRomVersion(), fallback == null ? null : fallback.getRomVersion(), target::setRomVersion);
        mergeIfText(primary == null ? null : primary.getOsVersion(), fallback == null ? null : fallback.getOsVersion(), target::setOsVersion);
        mergeIfText(primary == null ? null : primary.getOsApi(), fallback == null ? null : fallback.getOsApi(), target::setOsApi);
    }

    private static void copyIfText(String value, Consumer<String> setter) {
        if (setter == null) {
            return;
        }
        String trimmed = normalizeNullable(value);
        if (trimmed == null) {
            return;
        }
        setter.accept(trimmed);
    }

    private static void mergeIfText(String primaryValue, String fallbackValue, Consumer<String> setter) {
        if (setter == null) {
            return;
        }
        String merged = Texts.defaultIfBlank(normalizeNullable(primaryValue), normalizeNullable(fallbackValue));
        if (merged != null) {
            setter.accept(merged);
        }
    }

    private DeviceProfile resolveBootstrapProfile() {
        if (devicePool == null || devicePool.isEmpty()) {
            return null;
        }

        int limit = Math.max(1, Math.min(devicePoolSize, devicePool.size()));
        String startupName = normalizeNullable(this.devicePoolStartupName);
        if (startupName != null) {
            for (int i = 0; i < limit; i++) {
                DeviceProfile profile = devicePool.get(i);
                if (startupName.equals(normalizeNullable(profile == null ? null : profile.getName()))) {
                    return profile;
                }
            }
        }

        return devicePool.get(0);
    }

    private void validateRuntimeConfiguration() {
        requireTextValue(this.baseUrl, "fq.api.base-url");
        requireTextValue(this.userAgent, "fq.api.user-agent");
        requireTextValue(this.cookie, "fq.api.cookie");
        validateRequiredDevice(this.device, DEVICE_CONFIG_PREFIX);
        validateDevicePool();
    }

    private void validateDevicePool() {
        if (devicePool == null || devicePool.isEmpty()) {
            return;
        }
        for (int i = 0; i < devicePool.size(); i++) {
            DeviceProfile profile = devicePool.get(i);
            String prefix = DEVICE_POOL_PREFIX + "[" + i + "]";
            validateDevicePoolEntry(profile, prefix);
        }
    }

    private static void validateDevicePoolEntry(DeviceProfile profile, String prefix) {
        if (profile == null) {
            throw new IllegalStateException("缺少设备池配置: " + prefix);
        }
        requireTextValue(profile.getUserAgent(), prefix + ".user-agent");
        requireTextValue(profile.getCookie(), prefix + ".cookie");
        Device profileDevice = profile.getDevice();
        if (profileDevice == null) {
            throw new IllegalStateException("缺少设备配置: " + prefix + ".device");
        }
        validateRequiredDevice(profileDevice, prefix + ".device");
    }

    private static void validateRequiredDevice(Device device, String prefix) {
        if (device == null) {
            throw new IllegalStateException("缺少设备配置: " + prefix);
        }
        requireDeviceValue(device.getInstallId(), prefix + ".install-id");
        requireDeviceValue(device.getDeviceId(), prefix + ".device-id");
        requireDeviceValue(device.getAid(), prefix + ".aid");
        requireDeviceValue(device.getUpdateVersionCode(), prefix + ".update-version-code");
    }

    private static void requireDeviceValue(String value, String fieldName) {
        if (!Texts.hasText(value)) {
            throw new IllegalStateException("缺少设备配置字段: " + fieldName);
        }
    }

    private static void requireTextValue(String value, String fieldName) {
        if (!Texts.hasText(value)) {
            throw new IllegalStateException("缺少配置字段: " + fieldName);
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = normalizeNullable(baseUrl);
    }

    public Device getDevice() {
        return device;
    }

    public List<DeviceProfile> getDevicePool() {
        return devicePool;
    }

    public void setDevicePool(List<DeviceProfile> devicePool) {
        this.devicePool = devicePool;
    }

    public int getDevicePoolSize() {
        return devicePoolSize;
    }

    public void setDevicePoolSize(int devicePoolSize) {
        this.devicePoolSize = devicePoolSize;
    }

    public boolean isDevicePoolShuffleOnStartup() {
        return devicePoolShuffleOnStartup;
    }

    public void setDevicePoolShuffleOnStartup(boolean devicePoolShuffleOnStartup) {
        this.devicePoolShuffleOnStartup = devicePoolShuffleOnStartup;
    }

    public String getDevicePoolStartupName() {
        return devicePoolStartupName;
    }

    public void setDevicePoolStartupName(String devicePoolStartupName) {
        this.devicePoolStartupName = devicePoolStartupName;
    }

    public boolean isDevicePoolProbeOnStartup() {
        return devicePoolProbeOnStartup;
    }

    public void setDevicePoolProbeOnStartup(boolean devicePoolProbeOnStartup) {
        this.devicePoolProbeOnStartup = devicePoolProbeOnStartup;
    }

    public int getDevicePoolProbeMaxAttempts() {
        return devicePoolProbeMaxAttempts;
    }

    public void setDevicePoolProbeMaxAttempts(int devicePoolProbeMaxAttempts) {
        this.devicePoolProbeMaxAttempts = devicePoolProbeMaxAttempts;
    }

    public long getDeviceRotateCooldownMs() {
        return deviceRotateCooldownMs;
    }

    public void setDeviceRotateCooldownMs(long deviceRotateCooldownMs) {
        this.deviceRotateCooldownMs = deviceRotateCooldownMs;
    }

    public int getRegisterKeyCacheMaxEntries() {
        return registerKeyCacheMaxEntries;
    }

    public void setRegisterKeyCacheMaxEntries(int registerKeyCacheMaxEntries) {
        this.registerKeyCacheMaxEntries = registerKeyCacheMaxEntries;
    }

    public long getRegisterKeyCacheTtlMs() {
        return registerKeyCacheTtlMs;
    }

    public void setRegisterKeyCacheTtlMs(long registerKeyCacheTtlMs) {
        this.registerKeyCacheTtlMs = registerKeyCacheTtlMs;
    }

    public void setRuntimeProfile(RuntimeProfile runtimeProfile) {
        this.runtimeProfile = runtimeProfile;
    }

    public static final class RuntimeProfile {
        private final String userAgent;
        private final String cookie;
        private final Device device;

        private RuntimeProfile(String userAgent, String cookie, Device device) {
            this.userAgent = userAgent;
            this.cookie = cookie;
            this.device = copyDevice(device);
        }

        private static RuntimeProfile of(String userAgent, String cookie, Device device) {
            return new RuntimeProfile(normalizeNullable(userAgent), normalizeNullable(cookie), device);
        }

        public String getUserAgent() {
            return userAgent;
        }

        public String getCookie() {
            return cookie;
        }

        public Device getDevice() {
            return copyDevice(this.device);
        }

        /**
         * 高频只读场景使用：返回运行时快照中的设备引用，避免重复拷贝分配。
         * 调用方必须只读，不得修改返回对象。
         */
        public Device getDeviceUnsafe() {
            return this.device;
        }
    }

    public static class DeviceProfile {
        /**
         * 可选：用于日志识别
         */
        private String name;

        private String userAgent;
        private String cookie;
        private Device device = new Device();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public String getCookie() {
            return cookie;
        }

        public void setCookie(String cookie) {
            this.cookie = cookie;
        }

        public Device getDevice() {
            return device;
        }

        public void setDevice(Device device) {
            this.device = device;
        }
    }
    
    public static class Device {
        /**
         * 设备唯一标识符
         */
        private String cdid;
        
        /**
         * 安装ID
         */
        private String installId;
        
        /**
         * 设备ID
         */
        private String deviceId;
        
        /**
         * 应用ID
         */
        private String aid;
        
        /**
         * 版本代码
         */
        private String versionCode;
        
        /**
         * 版本名称
         */
        private String versionName;
        
        /**
         * 更新版本代码
         */
        private String updateVersionCode;
        
        /**
         * 设备类型
         */
        private String deviceType;
        
        /**
         * 设备品牌
         */
        private String deviceBrand;
        
        /**
         * ROM版本
         */
        private String romVersion;
        
        /**
         * 分辨率
         */
        private String resolution;
        
        /**
         * DPI
         */
        private String dpi;
        
        /**
         * 主机ABI
         */
        private String hostAbi;

        /**
         * Android 版本（例如 13）
         */
        private String osVersion;

        /**
         * Android API（例如 32）
         */
        private String osApi;

        public String getCdid() {
            return cdid;
        }

        public void setCdid(String cdid) {
            this.cdid = cdid;
        }

        public String getInstallId() {
            return installId;
        }

        public void setInstallId(String installId) {
            this.installId = installId;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }

        public String getAid() {
            return aid;
        }

        public void setAid(String aid) {
            this.aid = aid;
        }

        public String getVersionCode() {
            return versionCode;
        }

        public void setVersionCode(String versionCode) {
            this.versionCode = versionCode;
        }

        public String getVersionName() {
            return versionName;
        }

        public void setVersionName(String versionName) {
            this.versionName = versionName;
        }

        public String getUpdateVersionCode() {
            return updateVersionCode;
        }

        public void setUpdateVersionCode(String updateVersionCode) {
            this.updateVersionCode = updateVersionCode;
        }

        public String getDeviceType() {
            return deviceType;
        }

        public void setDeviceType(String deviceType) {
            this.deviceType = deviceType;
        }

        public String getDeviceBrand() {
            return deviceBrand;
        }

        public void setDeviceBrand(String deviceBrand) {
            this.deviceBrand = deviceBrand;
        }

        public String getRomVersion() {
            return romVersion;
        }

        public void setRomVersion(String romVersion) {
            this.romVersion = romVersion;
        }

        public String getResolution() {
            return resolution;
        }

        public void setResolution(String resolution) {
            this.resolution = resolution;
        }

        public String getDpi() {
            return dpi;
        }

        public void setDpi(String dpi) {
            this.dpi = dpi;
        }

        public String getHostAbi() {
            return hostAbi;
        }

        public void setHostAbi(String hostAbi) {
            this.hostAbi = hostAbi;
        }

        public String getOsVersion() {
            return osVersion;
        }

        public void setOsVersion(String osVersion) {
            this.osVersion = osVersion;
        }

        public String getOsApi() {
            return osApi;
        }

        public void setOsApi(String osApi) {
            this.osApi = osApi;
        }
    }
}

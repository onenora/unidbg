package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.FQApiProperties;
import com.mengying.fqnovel.dto.FQSearchRequest;
import com.mengying.fqnovel.dto.FQSearchResponse;
import com.mengying.fqnovel.utils.CookieUtils;
import com.mengying.fqnovel.utils.FQApiUtils;
import com.mengying.fqnovel.utils.FQSearchResponseParser;
import com.mengying.fqnovel.utils.Texts;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 设备信息风控（ILLEGAL_ACCESS）时的自愈：自动更换设备信息并刷新 registerkey。
 * 不写入配置文件、不重启进程，仅在内存中生效。
 */
@Service
public class FQDeviceRotationService {

    private static final Logger log = LoggerFactory.getLogger(FQDeviceRotationService.class);

    private final FQApiProperties fqApiProperties;
    private final FQRegisterKeyService registerKeyService;
    private final FQApiUtils fqApiUtils;
    private final FQSearchRequestEnricher searchRequestEnricher;
    private final UpstreamSignedRequestService upstreamSignedRequestService;

    public FQDeviceRotationService(
        FQApiProperties fqApiProperties,
        FQRegisterKeyService registerKeyService,
        FQApiUtils fqApiUtils,
        FQSearchRequestEnricher searchRequestEnricher,
        UpstreamSignedRequestService upstreamSignedRequestService
    ) {
        this.fqApiProperties = fqApiProperties;
        this.registerKeyService = registerKeyService;
        this.fqApiUtils = fqApiUtils;
        this.searchRequestEnricher = searchRequestEnricher;
        this.upstreamSignedRequestService = upstreamSignedRequestService;
    }

    private final ReentrantLock lock = new ReentrantLock();
    private volatile long lastRotateAtMs = 0L;
    private volatile String currentProfileName = "";
    private final AtomicInteger poolIndex = new AtomicInteger(0);

    public String getCurrentProfileName() {
        return currentProfileName;
    }

    private static String profileName(FQApiProperties.DeviceProfile profile) {
        return profile == null ? null : profile.getName();
    }

    private static FQApiProperties.Device runtimeDevice(FQApiProperties.RuntimeProfile runtimeProfile) {
        return runtimeProfile == null ? null : runtimeProfile.getDeviceUnsafe();
    }

    @PostConstruct
    public void initDevicePoolOnStartup() {
        List<FQApiProperties.DeviceProfile> pool = fqApiProperties.getDevicePool();
        if (pool == null || pool.isEmpty()) {
            return;
        }

        int limit = Math.max(1, fqApiProperties.getDevicePoolSize());
        if (pool.size() > limit) {
            pool = new ArrayList<>(pool.subList(0, limit));
            fqApiProperties.setDevicePool(pool);
        }

        String startupName = Texts.trimToNull(fqApiProperties.getDevicePoolStartupName());

        int selectedIndex = findByName(pool, startupName);

        // 若未指定启动设备，则按配置选择随机/首个
        if (selectedIndex < 0 && fqApiProperties.isDevicePoolShuffleOnStartup() && pool.size() > 1) {
            Collections.shuffle(pool, ThreadLocalRandom.current());
        }
        if (selectedIndex < 0) {
            selectedIndex = 0;
        }

        // 启动探测：随机启动但跳过“启动就风控”的设备（例如 search 无 search_id）
        if (selectedIndex >= 0 && fqApiProperties.isDevicePoolProbeOnStartup() && pool.size() > 1) {
            int maxAttempts = Math.max(1, fqApiProperties.getDevicePoolProbeMaxAttempts());
            maxAttempts = Math.min(maxAttempts, pool.size());

            int attempt = 0;
            int startIdx = selectedIndex;
            int idx = startIdx;
            boolean ok = false;
            while (attempt < maxAttempts) {
                FQApiProperties.DeviceProfile candidate = pool.get(idx);
                applyDeviceProfile(candidate);
                attempt++;
                ok = probeSearchOk();
                if (ok) {
                    selectedIndex = idx;
                    break;
                }
                idx = (idx + 1) % pool.size();
            }

            if (!ok) {
                // 兜底：探测全失败时，仍按原逻辑使用当前 selectedIndex
                FQApiProperties.DeviceProfile fallback = pool.get(selectedIndex);
                applyDeviceProfile(fallback);
                log.warn("启动设备探测失败，回退设备：设备名={}", profileName(fallback));
            } else {
                log.info("设备探测通过：序号={}, 尝试={}", selectedIndex, attempt);
            }
        } else {
            FQApiProperties.DeviceProfile selected = pool.get(selectedIndex);
            applyDeviceProfile(selected);
        }

        poolIndex.set(Math.floorMod(selectedIndex + 1, pool.size()));

        FQApiProperties.DeviceProfile selected = pool.get(selectedIndex);
        String name = profileName(selected);
        String deviceId = profileDeviceId(selected);
        log.info("选择设备：{} (ID={})", name, deviceId);
    }

    private int findByName(List<FQApiProperties.DeviceProfile> pool, String name) {
        if (pool == null || pool.isEmpty() || !Texts.hasText(name)) {
            return -1;
        }
        for (int i = 0; i < pool.size(); i++) {
            FQApiProperties.DeviceProfile profile = pool.get(i);
            String normalizedProfileName = Texts.trimToNull(profileName(profile));
            if (normalizedProfileName == null) {
                continue;
            }
            if (name.equals(normalizedProfileName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 启动轻量探测：发起一次 search 请求，要求返回 code=0 且包含 search_id 或 books 列表。
     * 不做解密/不依赖 registerkey，仅用于剔除“启动就被拦截”的设备指纹。
     */
    private boolean probeSearchOk() {
        try {
            FQSearchRequest searchRequest = new FQSearchRequest();
            searchRequest.setQuery("系统");
            searchRequest.setOffset(0);
            searchRequest.setCount(1);
            searchRequest.setTabType(1);
            searchRequest.setPassback(0);
            searchRequest.setIsFirstEnterSearch(true);
            searchRequestEnricher.enrich(searchRequest);

            String url = fqApiUtils.getSearchApiBaseUrl()
                + "/reading/bookapi/search/tab/v";
            Map<String, String> params = fqApiUtils.buildSearchParams(searchRequest);
            String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

            Map<String, String> headers = fqApiUtils.buildSearchHeaders();
            UpstreamSignedRequestService.UpstreamJsonResult upstream = upstreamSignedRequestService.executeSignedJsonGet(fullUrl, headers);
            if (upstream == null) {
                return false;
            }

            String body = upstream.responseBody();
            String trimmedBody = Texts.trimToNull(body);
            if (trimmedBody == null || trimmedBody.startsWith("<")) {
                return false;
            }

            JsonNode root = upstream.jsonBody();
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                return false;
            }
            String searchId = FQSearchResponseParser.deepFindSearchId(root);
            if (Texts.hasText(searchId)) {
                return true;
            }

            // 支持 search_tabs / data.books 等结构
            FQSearchResponse parsed = FQSearchResponseParser.parseSearchResponse(root, 1);
            if (parsed != null && parsed.getBooks() != null && !parsed.getBooks().isEmpty()) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 尝试旋转设备（带冷却时间，避免并发风暴）。
     *
     * @return true 表示完成设备切换，false 表示未切换
     */
    public boolean rotateIfNeeded(String reason) {
        return rotateInternal(reason, false);
    }

    /**
     * 强制旋转设备（忽略冷却时间），用于单次请求内的自愈流程。
     * 仍然会加锁，避免并发下“乱序切换”。
     */
    public boolean forceRotate(String reason) {
        return rotateInternal(reason, true);
    }

    private boolean rotateInternal(String reason, boolean ignoreCooldown) {
        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0L, fqApiProperties.getDeviceRotateCooldownMs());
        if (isInRotateCooldown(ignoreCooldown, now, cooldownMs)) {
            return false;
        }

        lock.lock();
        try {
            now = System.currentTimeMillis();
            cooldownMs = Math.max(0L, fqApiProperties.getDeviceRotateCooldownMs());
            if (isInRotateCooldown(ignoreCooldown, now, cooldownMs)) {
                return false;
            }

            if (!rotateFromConfiguredPool(reason)) {
                log.warn("检测到异常，但设备池中无可切换设备：原因={}", reason);
                return false;
            }

            lastRotateAtMs = now;
            refreshRegisterKeyAfterRotation();

            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean rotateFromConfiguredPool(String reason) {
        List<FQApiProperties.DeviceProfile> pool = fqApiProperties.getDevicePool();
        if (pool == null || pool.isEmpty()) {
            return false;
        }

        FQApiProperties.RuntimeProfile currentRuntime = fqApiProperties.getRuntimeProfile();
        FQApiProperties.Device currentDevice = runtimeDevice(currentRuntime);
        String currentDeviceId = deviceIdOf(currentDevice);
        String currentInstallId = installIdOf(currentDevice);

        FQApiProperties.DeviceProfile profile = null;
        int idx = -1;
        for (int i = 0; i < pool.size(); i++) {
            // 使用 Math.floorMod 自动处理整数溢出，确保索引始终在有效范围内
            int candidateIdx = Math.floorMod(poolIndex.getAndIncrement(), pool.size());
            FQApiProperties.DeviceProfile candidate = pool.get(candidateIdx);
            if (candidate == null) {
                continue;
            }

            String candidateDeviceId = profileDeviceId(candidate);
            String candidateInstallId = profileInstallId(candidate);
            if (isSamePhysicalDevice(candidateDeviceId, candidateInstallId, currentDeviceId, currentInstallId)) {
                continue;
            }

            profile = candidate;
            idx = candidateIdx;
            break;
        }

        if (profile == null) {
            return false;
        }

        applyDeviceProfile(profile);

        String name = profileName(profile);
        String deviceId = profileDeviceId(profile);
        String installId = profileInstallId(profile);
        log.warn("检测到异常，已切换设备：序号={}, 设备名={}, 设备ID={}, 安装ID={}, 原因={}",
            idx, name, deviceId, installId, reason);

        return true;
    }

    private static String deviceField(FQApiProperties.Device device, Function<FQApiProperties.Device, String> getter) {
        return device == null ? null : getter.apply(device);
    }

    private static String profileDeviceId(FQApiProperties.DeviceProfile profile) {
        return deviceField(profile == null ? null : profile.getDevice(), FQApiProperties.Device::getDeviceId);
    }

    private static String profileInstallId(FQApiProperties.DeviceProfile profile) {
        return deviceField(profile == null ? null : profile.getDevice(), FQApiProperties.Device::getInstallId);
    }

    private static String deviceIdOf(FQApiProperties.Device device) {
        return deviceField(device, FQApiProperties.Device::getDeviceId);
    }

    private static String installIdOf(FQApiProperties.Device device) {
        return deviceField(device, FQApiProperties.Device::getInstallId);
    }

    private void applyDeviceProfile(FQApiProperties.DeviceProfile profile) {
        if (profile == null) {
            return;
        }

        // 避免“部分应用”：如果 device 为空但 UA/cookie 已更新，会导致请求指纹不一致
        if (profile.getDevice() == null) {
            log.warn("设备配置的 device 字段为空，跳过设备信息应用: profileName={}", profileName(profile));
            return;
        }

        currentProfileName = Texts.nullToEmpty(profileName(profile));
        
        // 先准备好新的 Device 对象（避免逐字段修改导致并发读取到"半旧半新"状态）
        FQApiProperties.Device newDevice = new FQApiProperties.Device();
        FQApiProperties.Device src = profile.getDevice();
        copyDeviceFields(src, newDevice);

        FQApiProperties.RuntimeProfile currentRuntime = fqApiProperties.getRuntimeProfile();
        String userAgent = Texts.trimToNull(profile.getUserAgent());
        String cookie = Texts.trimToNull(profile.getCookie());
        if (userAgent == null || cookie == null) {
            log.warn("设备配置缺少完整请求指纹，跳过设备切换: profileName={}, userAgentPresent={}, cookiePresent={}, currentProfile={}",
                profileName(profile),
                userAgent != null,
                cookie != null,
                currentRuntime == null ? null : currentProfileName);
            return;
        }
        String normalizedCookie = CookieUtils.normalizeInstallId(cookie, newDevice.getInstallId());

        fqApiProperties.applyRuntimeProfile(userAgent, normalizedCookie, newDevice);
    }

    private boolean isInRotateCooldown(boolean ignoreCooldown, long nowMs, long cooldownMs) {
        return !ignoreCooldown && nowMs - lastRotateAtMs < cooldownMs;
    }

    private static boolean isSamePhysicalDevice(
        String candidateDeviceId,
        String candidateInstallId,
        String currentDeviceId,
        String currentInstallId
    ) {
        boolean sameDeviceId = candidateDeviceId != null && candidateDeviceId.equals(currentDeviceId);
        boolean sameInstallId = candidateInstallId != null && candidateInstallId.equals(currentInstallId);
        return sameDeviceId && sameInstallId;
    }

    private void refreshRegisterKeyAfterRotation() {
        try {
            registerKeyService.invalidateCurrentKey();
            registerKeyService.refreshRegisterKey();
        } catch (Exception e) {
            log.warn("设备旋转后刷新 registerkey 失败（可忽略，下次请求会再刷新）", e);
        }
    }

    private static void copyDeviceFields(FQApiProperties.Device src, FQApiProperties.Device target) {
        copyIfPresent(src.getAid(), target::setAid);
        copyIfPresent(src.getCdid(), target::setCdid);
        copyIfPresent(src.getDeviceBrand(), target::setDeviceBrand);
        copyIfPresent(src.getDeviceId(), target::setDeviceId);
        copyIfPresent(src.getDeviceType(), target::setDeviceType);
        copyIfPresent(src.getDpi(), target::setDpi);
        copyIfPresent(src.getHostAbi(), target::setHostAbi);
        copyIfPresent(src.getInstallId(), target::setInstallId);
        copyIfPresent(src.getResolution(), target::setResolution);
        copyIfPresent(src.getRomVersion(), target::setRomVersion);
        copyIfPresent(src.getUpdateVersionCode(), target::setUpdateVersionCode);
        copyIfPresent(src.getVersionCode(), target::setVersionCode);
        copyIfPresent(src.getVersionName(), target::setVersionName);
        copyIfPresent(src.getOsVersion(), target::setOsVersion);
        copyIfPresent(src.getOsApi(), target::setOsApi);
    }

    private static void copyIfPresent(String value, Consumer<String> setter) {
        String v = Texts.trimToNull(value);
        if (v == null) {
            return;
        }
        setter.accept(v);
    }
}

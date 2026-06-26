package interview.guide.modules.llmprovider.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.*;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * LLM Provider 配置服务。
 * <p>
 * 管理所有 LLM 服务商的连接配置，支持运行时增删改查。
 * <p>
 * <b>存储方式：</b>数据库（{@code llm_providers} 表 + {@code llm_global_settings} 表）。
 * API Key 使用 Base64 编码后存储，查询时返回脱敏版。
 * <p>
 * <b>启动初始化：</b>首次启动时自动创建一条默认设置记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderConfigService {

    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;

    // ==================================================================
    // 启动初始化
    // ==================================================================

    /**
     * 首次启动时，如果全局设置表为空，自动创建一条默认记录。
     * 默认聊天 Provider 设为 dashscope，向量化 Provider 也设为 dashscope。
     */
    @PostConstruct
    public void init() {
        if (globalSettingRepository.count() == 0) {
            LlmGlobalSettingEntity settings = new LlmGlobalSettingEntity();
            settings.setDefaultProvider("dashscope");
            settings.setDefaultEmbeddingProvider("dashscope");
            globalSettingRepository.save(settings);
            log.info("已创建默认 LLM 全局设置");
        }
    }

    // ==================================================================
    // Provider CRUD
    // ==================================================================

    /**
     * 获取所有 Provider 列表。
     * <p>
     * 返回的 apiKey 已脱敏，不可逆。
     * 同时标注每个 Provider 是否为默认聊天/向量化 Provider。
     */
    public List<ProviderDTO> listProviders() {
        LlmGlobalSettingEntity settings = getSettings();
        return providerRepository.findAll().stream()
            .map(entity -> toDTO(entity, settings))
            .toList();
    }

    /**
     * 获取单个 Provider 详情。
     *
     * @param id Provider 标识（providerId）
     */
    public ProviderDTO getProvider(String id) {
        LlmProviderEntity entity = providerRepository.findByProviderId(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Provider 不存在: " + id));
        return toDTO(entity, getSettings());
    }

    /**
     * 新增 Provider。
     * <p>
     * providerId 不可重复。apiKey 自动加密存储。
     */
    @Transactional
    public void createProvider(CreateProviderRequest request) {
        if (providerRepository.existsByProviderId(request.id())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Provider ID 已存在: " + request.id());
        }

        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setProviderId(request.id());
        entity.setBaseUrl(request.baseUrl());
        entity.setEncryptedApiKey(encryptionService.encrypt(request.apiKey()));
        entity.setModel(request.model());
        entity.setEmbeddingModel(request.embeddingModel());
        entity.setEmbeddingDimensions(request.embeddingDimensions());
        entity.setSupportsEmbedding(request.supportsEmbedding() != null && request.supportsEmbedding());
        entity.setTemperature(request.temperature());

        providerRepository.save(entity);
        log.info("创建 Provider: id={}, model={}", request.id(), request.model());
    }

    /**
     * 更新 Provider。
     * <p>
     * 所有字段可选——apiKey 传 null 表示不修改，传空字符串表示清空。
     */
    @Transactional
    public void updateProvider(String id, UpdateProviderRequest request) {
        LlmProviderEntity entity = providerRepository.findByProviderId(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Provider 不存在: " + id));

        if (request.baseUrl() != null) entity.setBaseUrl(request.baseUrl());
        if (request.apiKey() != null) {
            entity.setEncryptedApiKey(request.apiKey().isBlank() ? "" : encryptionService.encrypt(request.apiKey()));
        }
        if (request.model() != null) entity.setModel(request.model());
        if (request.embeddingModel() != null) entity.setEmbeddingModel(request.embeddingModel());
        if (request.embeddingDimensions() != null) entity.setEmbeddingDimensions(request.embeddingDimensions());
        if (request.supportsEmbedding() != null) entity.setSupportsEmbedding(request.supportsEmbedding());
        if (request.temperature() != null) entity.setTemperature(request.temperature());

        providerRepository.save(entity);
        log.info("更新 Provider: id={}", id);
    }

    /**
     * 删除 Provider。
     * <p>
     * 如果该 Provider 正在被设为默认 Provider，自动清除默认设置。
     */
    @Transactional
    public void deleteProvider(String id) {
        if (!providerRepository.existsByProviderId(id)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Provider 不存在: " + id);
        }
        providerRepository.deleteByProviderId(id);

        // 如果删除的是默认 Provider，清除对应的默认设置
        LlmGlobalSettingEntity settings = getSettings();
        boolean changed = false;
        if (id.equals(settings.getDefaultProvider())) {
            settings.setDefaultProvider(null);
            changed = true;
        }
        if (id.equals(settings.getDefaultEmbeddingProvider())) {
            settings.setDefaultEmbeddingProvider(null);
            changed = true;
        }
        if (changed) {
            globalSettingRepository.save(settings);
        }
        log.info("删除 Provider: id={}", id);
    }

    /**
     * 测试 Provider 连通性。
     * <p>
     * 向该 Provider 发送一条简单的对话请求，检查是否能正常返回。
     * TODO: 当前为骨架，后续实现真实的 ChatClient 调用。
     */
    public ProviderTestResult testProvider(String id) {
        LlmProviderEntity entity = providerRepository.findByProviderId(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Provider 不存在: " + id));

        // 解密 API Key 后用 ChatClient 发一条简单请求测试连通性
        // String apiKey = encryptionService.decrypt(entity.getEncryptedApiKey());
        // TODO: 动态创建 ChatClient，发送 "Hello" 测试连通性

        return new ProviderTestResult(true, "测试功能待实现，已确认 Provider 配置存在", entity.getModel());
    }

    /**
     * 重新加载 Provider 配置。
     * <p>
     * 目前 Provider 直接读写数据库，此操作仅为状态刷新。
     * 后续如果引入配置文件缓存，此方法负责重新加载。
     */
    public void reloadProviders() {
        log.info("Provider 配置已重新加载");
    }

    // ==================================================================
    // 默认 Provider 管理
    // ==================================================================

    /** 获取当前的默认聊天 Provider 和默认向量化 Provider。 */
    public DefaultProviderDTO getDefaultProvider() {
        LlmGlobalSettingEntity settings = getSettings();
        return new DefaultProviderDTO(settings.getDefaultProvider(), settings.getDefaultEmbeddingProvider());
    }

    /** 设置默认聊天 Provider。 */
    @Transactional
    public void updateDefaultProvider(DefaultProviderDTO request) {
        LlmGlobalSettingEntity settings = getSettings();
        settings.setDefaultProvider(request.defaultProvider());
        globalSettingRepository.save(settings);
        log.info("更新默认聊天 Provider: {}", request.defaultProvider());
    }

    /** 设置默认向量化 Provider。 */
    @Transactional
    public void updateDefaultEmbeddingProvider(DefaultProviderDTO request) {
        LlmGlobalSettingEntity settings = getSettings();
        settings.setDefaultEmbeddingProvider(request.defaultEmbeddingProvider());
        globalSettingRepository.save(settings);
        log.info("更新默认向量化 Provider: {}", request.defaultEmbeddingProvider());
    }

    // ==================================================================
    // ASR 配置
    // ==================================================================

    /** 获取 ASR 配置（apiKey 脱敏后返回）。 */
    public AsrConfigDTO getAsrConfig() {
        LlmGlobalSettingEntity settings = getSettings();
        return AsrConfigDTO.builder()
            .url(settings.getAsrUrl())
            .model(settings.getAsrModel())
            .maskedApiKey(encryptionService.mask(encryptionService.decrypt(settings.getEncryptedAsrApiKey())))
            .language(settings.getAsrLanguage())
            .format(settings.getAsrFormat())
            .sampleRate(settings.getAsrSampleRate())
            .enableTurnDetection(settings.getAsrEnableTurnDetection())
            .turnDetectionType(settings.getAsrTurnDetectionType())
            .turnDetectionThreshold(settings.getAsrTurnDetectionThreshold())
            .turnDetectionSilenceDurationMs(settings.getAsrTurnDetectionSilenceDurationMs())
            .build();
    }

    /** 更新 ASR 配置。apiKey 传 null 表示不修改。 */
    @Transactional
    public void updateAsrConfig(AsrConfigRequest request) {
        LlmGlobalSettingEntity settings = getSettings();
        settings.setAsrUrl(request.url());
        settings.setAsrModel(request.model());
        if (request.apiKey() != null) {
            settings.setEncryptedAsrApiKey(encryptionService.encrypt(request.apiKey()));
        }
        if (request.language() != null) settings.setAsrLanguage(request.language());
        if (request.format() != null) settings.setAsrFormat(request.format());
        if (request.sampleRate() != null) settings.setAsrSampleRate(request.sampleRate());
        if (request.enableTurnDetection() != null) settings.setAsrEnableTurnDetection(request.enableTurnDetection());
        if (request.turnDetectionType() != null) settings.setAsrTurnDetectionType(request.turnDetectionType());
        if (request.turnDetectionThreshold() != null) settings.setAsrTurnDetectionThreshold(request.turnDetectionThreshold());
        if (request.turnDetectionSilenceDurationMs() != null) settings.setAsrTurnDetectionSilenceDurationMs(request.turnDetectionSilenceDurationMs());
        globalSettingRepository.save(settings);
        log.info("更新 ASR 配置");
    }

    // ==================================================================
    // TTS 配置
    // ==================================================================

    /** 获取 TTS 配置（apiKey 脱敏后返回）。 */
    public TtsConfigDTO getTtsConfig() {
        LlmGlobalSettingEntity settings = getSettings();
        return TtsConfigDTO.builder()
            .model(settings.getTtsModel())
            .maskedApiKey(encryptionService.mask(encryptionService.decrypt(settings.getEncryptedTtsApiKey())))
            .voice(settings.getTtsVoice())
            .format(settings.getTtsFormat())
            .sampleRate(settings.getTtsSampleRate())
            .mode(settings.getTtsMode())
            .languageType(settings.getTtsLanguageType())
            .speechRate(settings.getTtsSpeechRate())
            .volume(settings.getTtsVolume())
            .build();
    }

    /** 更新 TTS 配置。apiKey 传 null 表示不修改。 */
    @Transactional
    public void updateTtsConfig(TtsConfigRequest request) {
        LlmGlobalSettingEntity settings = getSettings();
        settings.setTtsModel(request.model());
        if (request.apiKey() != null) {
            settings.setEncryptedTtsApiKey(encryptionService.encrypt(request.apiKey()));
        }
        if (request.voice() != null) settings.setTtsVoice(request.voice());
        if (request.format() != null) settings.setTtsFormat(request.format());
        if (request.sampleRate() != null) settings.setTtsSampleRate(request.sampleRate());
        if (request.mode() != null) settings.setTtsMode(request.mode());
        if (request.languageType() != null) settings.setTtsLanguageType(request.languageType());
        if (request.speechRate() != null) settings.setTtsSpeechRate(request.speechRate());
        if (request.volume() != null) settings.setTtsVolume(request.volume());
        globalSettingRepository.save(settings);
        log.info("更新 TTS 配置");
    }

    /** 测试 ASR 连通性。TODO: 后续实现。 */
    public ProviderTestResult testAsrConfig() {
        return new ProviderTestResult(false, "ASR 测试功能待实现", null);
    }

    // ==================================================================
    // 内部方法
    // ==================================================================

    /** 获取全局设置记录（只有一条，ID=1）。不存在则创建默认记录。 */
    private LlmGlobalSettingEntity getSettings() {
        return globalSettingRepository.findAll().stream().findFirst()
            .orElseGet(() -> {
                LlmGlobalSettingEntity s = new LlmGlobalSettingEntity();
                s.setDefaultProvider("dashscope");
                s.setDefaultEmbeddingProvider("dashscope");
                return globalSettingRepository.save(s);
            });
    }

    /** Entity → DTO 转换。 */
    private ProviderDTO toDTO(LlmProviderEntity e, LlmGlobalSettingEntity settings) {
        String rawKey = encryptionService.decrypt(e.getEncryptedApiKey());
        return new ProviderDTO(
            e.getProviderId(),
            e.getBaseUrl(),
            encryptionService.mask(rawKey),
            e.getModel(),
            e.getEmbeddingModel(),
            e.getEmbeddingDimensions(),
            e.getSupportsEmbedding() != null && e.getSupportsEmbedding(),
            e.getTemperature(),
            e.getProviderId().equals(settings.getDefaultProvider()),
            e.getProviderId().equals(settings.getDefaultEmbeddingProvider())
        );
    }
}

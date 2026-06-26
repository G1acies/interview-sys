package interview.guide.modules.llmprovider.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.llmprovider.dto.*;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * LLM Provider 管理控制器。
 * <p>
 * ┌───────────────────────────────────────────────────────────────┐
 * │ Provider CRUD（6）                                              │
 * ├───────────────────────────────────────────────────────────────┤
 * │ GET    /api/llm-provider/list          所有 Provider 列表      │
 * │ GET    /api/llm-provider/{id}          单个 Provider 详情      │
 * │ POST   /api/llm-provider               新增 Provider           │
 * │ PUT    /api/llm-provider/{id}          修改 Provider           │
 * │ DELETE /api/llm-provider/{id}          删除 Provider           │
 * │ POST   /api/llm-provider/reload        重新加载配置             │
 * ├───────────────────────────────────────────────────────────────┤
 * │ 测试 & 默认值（4）                                              │
 * ├───────────────────────────────────────────────────────────────┤
 * │ POST   /api/llm-provider/{id}/test    测试连通性               │
 * │ GET    /api/llm-provider/default-provider   获取默认 Provider   │
 * │ PUT    /api/llm-provider/default-provider   设置默认 Provider   │
 * │ PUT    /api/llm-provider/default-embedding-provider 设置默认    │
 * │                                    向量化 Provider             │
 * ├───────────────────────────────────────────────────────────────┤
 * │ ASR/TTS 配置（5）                                              │
 * ├───────────────────────────────────────────────────────────────┤
 * │ GET    /api/llm-provider/voice/asr        获取 ASR 配置        │
 * │ PUT    /api/llm-provider/voice/asr        更新 ASR 配置        │
 * │ GET    /api/llm-provider/voice/tts        获取 TTS 配置        │
 * │ PUT    /api/llm-provider/voice/tts        更新 TTS 配置        │
 * │ POST   /api/llm-provider/voice/asr/test  测试 ASR 连通性       │
 * └───────────────────────────────────────────────────────────────┘
 */
@Slf4j
@RestController
@RequestMapping("/api/llm-provider")
@RequiredArgsConstructor
public class LlmProviderController {

    private final LlmProviderConfigService configService;

    // ==================================================================
    // Provider CRUD
    // ==================================================================

    /**
     * 获取所有 Provider 列表。
     * <p>
     * 返回每个 Provider 的基本信息，apiKey 已脱敏。
     * 同时标注每个 Provider 是否为默认聊天/向量化 Provider。
     */
    @GetMapping("/list")
    public Result<List<ProviderDTO>> listProviders() {
        return Result.success(configService.listProviders());
    }

    /**
     * 获取单个 Provider 详情。
     *
     * @param id Provider 标识（如 "dashscope"）
     */
    @GetMapping("/{id}")
    public Result<ProviderDTO> getProvider(@PathVariable String id) {
        return Result.success(configService.getProvider(id));
    }

    /**
     * 新增 Provider。
     * <p>
     * providerId 不可重复。apiKey 自动加密存储。
     * 每个 Provider 包含 baseUrl、apiKey、model，以及可选的 embedding 配置。
     */
    @PostMapping
    public Result<Void> createProvider(@Valid @RequestBody CreateProviderRequest request) {
        configService.createProvider(request);
        return Result.success();
    }

    /**
     * 修改 Provider。
     * <p>
     * 所有字段可选——仅传需要修改的字段，未传的字段保持不变。
     * apiKey 传 null = 不修改，传空字符串 = 清空。
     *
     * @param id      Provider ID（不可改）
     * @param request 需要修改的字段
     */
    @PutMapping("/{id}")
    public Result<Void> updateProvider(@PathVariable String id, @RequestBody UpdateProviderRequest request) {
        configService.updateProvider(id, request);
        return Result.success();
    }

    /**
     * 删除 Provider。
     * <p>
     * 如果该 Provider 是当前默认聊天或默认向量化 Provider，
     * 删除后自动清除对应的默认设置。
     *
     * @param id Provider ID
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteProvider(@PathVariable String id) {
        configService.deleteProvider(id);
        return Result.success();
    }

    /**
     * 测试 Provider 连通性。
     * <p>
     * 向该 Provider 发送一条简单的对话请求，检查是否能正常返回。
     * 成功时返回使用的模型名，失败时返回错误原因。
     *
     * @param id Provider ID
     */
    @PostMapping("/{id}/test")
    public Result<ProviderTestResult> testProvider(@PathVariable String id) {
        return Result.success(configService.testProvider(id));
    }

    /**
     * 重新加载 Provider 配置。
     * <p>
     * 当配置来源改为文件时，此接口用于触发重新读取。
     * 当前配置直接读写数据库，此操作为预留接口。
     */
    @PostMapping("/reload")
    public Result<Void> reloadProviders() {
        configService.reloadProviders();
        return Result.success();
    }

    // ==================================================================
    // 默认 Provider 管理
    // ==================================================================

    /** 获取默认聊天 Provider 和默认向量化 Provider 的 ID。 */
    @GetMapping("/default-provider")
    public Result<DefaultProviderDTO> getDefaultProvider() {
        return Result.success(configService.getDefaultProvider());
    }

    /**
     * 设置默认聊天 Provider。
     * <p>
     * 前端在设置页下拉选择后调用此接口。
     */
    @PutMapping("/default-provider")
    public Result<Void> updateDefaultProvider(@RequestBody DefaultProviderDTO request) {
        configService.updateDefaultProvider(request);
        return Result.success();
    }

    /**
     * 设置默认向量化 Provider。
     * <p>
     * 与聊天 Provider 分离——有些 Provider 只支持聊天不支持向量化。
     */
    @PutMapping("/default-embedding-provider")
    public Result<Void> updateDefaultEmbeddingProvider(@RequestBody DefaultProviderDTO request) {
        configService.updateDefaultEmbeddingProvider(request);
        return Result.success();
    }

    // ==================================================================
    // ASR/TTS 语音配置
    // ==================================================================

    /**
     * 获取 ASR（语音转文字）配置。
     * <p>
     * 返回当前的 ASR WebSocket URL、模型、采样率、VAD 参数等。
     * apiKey 以脱敏形式返回。
     */
    @GetMapping("/voice/asr")
    public Result<AsrConfigDTO> getAsrConfig() {
        return Result.success(configService.getAsrConfig());
    }

    /**
     * 更新 ASR 配置。
     * <p>
     * apiKey 传 null 表示不修改密钥。
     * 首次使用语音面试前必须先配置 ASR。
     */
    @PutMapping("/voice/asr")
    public Result<Void> updateAsrConfig(@RequestBody AsrConfigRequest request) {
        configService.updateAsrConfig(request);
        return Result.success();
    }

    /**
     * 获取 TTS（文字转语音）配置。
     * <p>
     * 返回当前的 TTS 模型、发音人、语速、音量等。
     */
    @GetMapping("/voice/tts")
    public Result<TtsConfigDTO> getTtsConfig() {
        return Result.success(configService.getTtsConfig());
    }

    /**
     * 更新 TTS 配置。
     * <p>
     * apiKey 传 null 表示不修改密钥。
     */
    @PutMapping("/voice/tts")
    public Result<Void> updateTtsConfig(@RequestBody TtsConfigRequest request) {
        configService.updateTtsConfig(request);
        return Result.success();
    }

    /**
     * 测试 ASR 连通性。
     * <p>
     * 用当前 ASR 配置尝试建立 WebSocket 连接，检查是否能正常通信。
     */
    @PostMapping("/voice/asr/test")
    public Result<ProviderTestResult> testAsrConfig() {
        return Result.success(configService.testAsrConfig());
    }
}

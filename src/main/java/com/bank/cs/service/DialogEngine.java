package com.bank.cs.service;

import com.bank.cs.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话引擎：核心编排器
 *
 * 完整流程:
 *   1. 检查是否有待确认的意图跳转
 *   2. LLM解析用户意图+槽位
 *   3. 根据意图类型分发:
 *      - 业务意图(当前有活跃帧) → 先确认是否跳转，而非直接切换
 *      - 业务意图(无活跃帧) → 直接开始
 *      - qa → 流式问答
 *      - none → 补充当前帧的槽位
 *   4. 业务完成后自动pop栈，恢复被挂起的业务并继续追问
 */
@Service
public class DialogEngine {

    private static final Logger log = LoggerFactory.getLogger(DialogEngine.class);

    private static final Set<String> CONFIRM_YES = Set.of(
            "是", "好", "好的", "可以", "行", "嗯", "对", "确认", "是的", "没问题",
            "yes", "ok", "y", "sure");
    private static final Set<String> CONFIRM_NO = Set.of(
            "不", "否", "不要", "不了", "算了", "取消", "还是不了", "先不",
            "no", "n", "cancel");

    private final DeepSeekService deepSeekService;
    private final IntentRegistry intentRegistry;
    private final SessionManager sessionManager;

    public DialogEngine(DeepSeekService deepSeekService,
                       IntentRegistry intentRegistry,
                       SessionManager sessionManager) {
        this.deepSeekService = deepSeekService;
        this.intentRegistry = intentRegistry;
        this.sessionManager = sessionManager;
    }

    /**
     * 处理用户消息，返回流式响应
     */
    public Flux<String> process(String sessionId, String userInput) {
        SessionContext ctx = sessionManager.getOrCreate(sessionId);
        ctx.addMessage(new ChatMessage(ChatMessage.Role.USER, userInput));

        // ========== Step 0: 检查是否在等待意图跳转确认 ==========
        if (ctx.hasPendingSwitch()) {
            return handleSwitchConfirmation(ctx, userInput);
        }

        // ========== Step 1: LLM意图识别 ==========
        String stackContext = buildStackContext(ctx);
        LlmParsedResult parsed;
        try {
            parsed = deepSeekService.parseIntent(
                    userInput, ctx.getRecentHistory(5), stackContext);
        } catch (Exception e) {
            log.error("Intent parse failed, fallback to QA", e);
            parsed = new LlmParsedResult();
            parsed.setIntent("qa");
        }

        log.info("[Session {}] intent={}, slots={}", sessionId, parsed.getIntent(), parsed.getSlots());

        // ========== Step 2: 分发处理 ==========
        String intent = parsed.getIntent();

        // 2a: 识别到业务意图
        if (isBusinessIntent(intent)) {
            if (ctx.hasActiveFrame()) {
                // 当前有活跃业务 → 不能直接跳转，先问用户确认
                return askSwitchConfirmation(ctx, parsed);
            } else {
                // 没有活跃业务 → 直接开始新业务
                return startBusinessIntent(ctx, parsed);
            }
        }

        // 2b: 当前有活跃业务帧，尝试填充槽位
        if (ctx.hasActiveFrame()) {
            return handleSlotFilling(ctx, parsed, userInput);
        }

        // 2c: QA问答（无活跃业务时）
        return handleQA(ctx, parsed, userInput);
    }

    // ==================== 意图跳转确认 ====================

    /**
     * 在业务进行中识别到新业务意图 → 询问用户是否要中断当前业务去办新业务
     */
    private Flux<String> askSwitchConfirmation(SessionContext ctx, LlmParsedResult parsed) {
        DialogFrame currentFrame = ctx.currentFrame();
        IntentRegistry.IntentConfig newConfig = intentRegistry.getIntent(parsed.getIntent());

        // 记录待确认状态
        ctx.setPendingSwitch(parsed.getIntent(), parsed.getSlots());

        // 构建已收集进度描述
        StringBuilder progress = new StringBuilder();
        for (Map.Entry<String, String> entry : currentFrame.getCollectedSlots().entrySet()) {
            SlotDefinition def = currentFrame.getSlotSchema().get(entry.getKey());
            if (def != null) {
                progress.append(def.getDisplayName()).append(": ").append(entry.getValue()).append("、");
            }
        }

        String response = "您当前正在办理【" + currentFrame.getIntentName() + "】" +
                (progress.length() > 0 ? "（已记录 " + progress.substring(0, progress.length() - 1) + "）" : "") +
                "，检测到您想办理【" + newConfig.getDisplayName() + "】。\n" +
                "是否先去办理【" + newConfig.getDisplayName() + "】？办理完成后会自动回到【" +
                currentFrame.getIntentName() + "】继续。\n" +
                "请回复【是】或【否】。";

        ctx.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, response));
        log.info("[Session {}] Asking switch confirmation: {} -> {}",
                ctx.getSessionId(), currentFrame.getIntentId(), parsed.getIntent());
        return Flux.just(response);
    }

    /**
     * 处理用户对意图跳转的确认/拒绝
     */
    private Flux<String> handleSwitchConfirmation(SessionContext ctx, String userInput) {
        String normalized = userInput.trim().toLowerCase();

        if (CONFIRM_YES.contains(normalized)) {
            // 用户确认跳转 → 执行切换
            String intentId = ctx.getPendingSwitchIntentId();
            Map<String, String> slots = ctx.getPendingSwitchSlots();
            ctx.clearPendingSwitch();

            IntentRegistry.IntentConfig config = intentRegistry.getIntent(intentId);
            DialogFrame frame = new DialogFrame(intentId, config.getDisplayName(), config.getSlotSchema());
            ctx.pushFrame(frame);
            log.info("[Session {}] Switch confirmed, pushed: {}", ctx.getSessionId(), frame);

            // 填充已提取到的槽位
            if (slots != null) {
                frame.fillSlots(slots);
            }

            return checkAndPromptSlots(ctx, frame);

        } else if (CONFIRM_NO.contains(normalized)) {
            // 用户拒绝跳转 → 继续当前业务
            ctx.clearPendingSwitch();
            DialogFrame frame = ctx.currentFrame();

            String response = "好的，继续为您办理【" + frame.getIntentName() + "】。";
            List<SlotDefinition> missing = frame.getMissingRequiredSlots();
            if (!missing.isEmpty()) {
                response += missing.get(0).getPrompt();
            }

            ctx.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, response));
            log.info("[Session {}] Switch rejected, continuing: {}", ctx.getSessionId(), frame);
            return Flux.just(response);

        } else {
            // 用户回复模糊 → 让LLM判断是确认还是拒绝，或者重新提问
            // 简单处理：再问一次
            String pendingIntentId = ctx.getPendingSwitchIntentId();
            IntentRegistry.IntentConfig config = intentRegistry.getIntent(pendingIntentId);

            String response = "抱歉没有听清，请问是否要先去办理【" + config.getDisplayName() + "】？请回复【是】或【否】。";
            ctx.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, response));
            return Flux.just(response);
        }
    }

    // ==================== 业务意图处理 ====================

    /**
     * 开始一个新的业务意图（无需确认的场景，当前没有活跃帧）
     */
    private Flux<String> startBusinessIntent(SessionContext ctx, LlmParsedResult parsed) {
        String intentId = parsed.getIntent();
        IntentRegistry.IntentConfig config = intentRegistry.getIntent(intentId);

        DialogFrame frame = new DialogFrame(intentId, config.getDisplayName(), config.getSlotSchema());
        ctx.pushFrame(frame);
        log.info("[Session {}] Started new business: {}", ctx.getSessionId(), frame);

        // 填充本轮可能已经提取到的槽位
        if (parsed.getSlots() != null) {
            frame.fillSlots(parsed.getSlots());
        }

        return checkAndPromptSlots(ctx, frame);
    }

    /**
     * 在活跃业务帧中填充槽位
     */
    private Flux<String> handleSlotFilling(SessionContext ctx, LlmParsedResult parsed, String userInput) {
        DialogFrame frame = ctx.currentFrame();

        if (frame == null) {
            return handleQA(ctx, parsed, userInput);
        }

        // 如果LLM识别到了新的业务意图（嵌套场景）→ 走确认流程
        if (isBusinessIntent(parsed.getIntent())) {
            return askSwitchConfirmation(ctx, parsed);
        }

        // 填充LLM提取到的槽位
        boolean filled = false;
        if (parsed.getSlots() != null) {
            filled = frame.fillSlots(parsed.getSlots());
        }

        // 如果有校验错误，提示用户重新输入
        if (frame.getLastValidationError() != null) {
            String error = frame.getLastValidationError();
            frame.clearValidationError();
            ctx.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, error));
            return Flux.just(error);
        }

        // 如果LLM没提取到有效slot值，尝试将用户输入作为第一个缺失槽位的值
        if (!filled && !frame.allRequiredSlotsFilled()) {
            List<SlotDefinition> missing = frame.getMissingRequiredSlots();
            if (!missing.isEmpty()) {
                SlotDefinition nextSlot = missing.get(0);
                // 先校验用户原始输入是否合法
                if (nextSlot.validate(userInput.trim())) {
                    frame.fillSlots(Map.of(nextSlot.getName(), userInput.trim()));
                } else if (nextSlot.getValidationRegex() != null) {
                    // 有校验规则但没通过 → 提示重新输入
                    String hint = nextSlot.getValidationHint() != null
                            ? nextSlot.getValidationHint()
                            : nextSlot.getDisplayName() + "格式不正确，请重新输入。";
                    ctx.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, hint));
                    return Flux.just(hint);
                }
                // 无校验规则 → 只有当输入看起来不像是一个新意图时才填入
                // （这里LLM已经判断intent不是业务意图了，所以可以安全填入）
                else {
                    frame.fillSlots(Map.of(nextSlot.getName(), userInput.trim()));
                }
            }
        }

        return checkAndPromptSlots(ctx, frame);
    }

    /**
     * 检查槽位收集状态，决定追问还是执行
     */
    private Flux<String> checkAndPromptSlots(SessionContext ctx, DialogFrame frame) {
        List<SlotDefinition> missing = frame.getMissingRequiredSlots();

        if (!missing.isEmpty()) {
            // 还有缺失的必填槽位 → 追问
            SlotDefinition nextSlot = missing.get(0);
            StringBuilder prompt = new StringBuilder();

            // 展示已收集信息 + 追问下一个
            if (!frame.getCollectedSlots().isEmpty()) {
                prompt.append("已确认 ");
                for (Map.Entry<String, String> entry : frame.getCollectedSlots().entrySet()) {
                    SlotDefinition def = frame.getSlotSchema().get(entry.getKey());
                    if (def != null) {
                        prompt.append(def.getDisplayName()).append(": ").append(entry.getValue()).append("，");
                    }
                }
            } else {
                prompt.append("好的，正在为您办理【").append(frame.getIntentName()).append("】。");
            }
            prompt.append(nextSlot.getPrompt());

            String response = prompt.toString();
            ctx.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, response));
            return Flux.just(response);
        }

        // 所有槽位已收集 → 进入确认执行
        frame.setState(DialogFrame.State.CONFIRMING);
        return handleConfirmAndExecute(ctx, frame);
    }

    /**
     * 确认并执行业务，完成后检查栈中是否有被挂起的业务需要恢复
     */
    private Flux<String> handleConfirmAndExecute(SessionContext ctx, DialogFrame frame) {
        StringBuilder confirmMsg = new StringBuilder();
        confirmMsg.append("好的，为您确认【").append(frame.getIntentName()).append("】信息：\n");
        for (Map.Entry<String, String> entry : frame.getCollectedSlots().entrySet()) {
            SlotDefinition def = frame.getSlotSchema().get(entry.getKey());
            if (def != null) {
                confirmMsg.append("  · ").append(def.getDisplayName()).append("：")
                          .append(entry.getValue()).append("\n");
            }
        }

        // 模拟执行
        frame.setState(DialogFrame.State.EXECUTING);
        confirmMsg.append("\n✅ 【").append(frame.getIntentName()).append("】业务办理成功！（模拟）");
        frame.setState(DialogFrame.State.DONE);

        // 检查是否有被挂起的帧需要恢复
        DialogFrame resumed = ctx.popAndResume();
        if (resumed != null) {
            confirmMsg.append("\n\n————————————————————\n");
            confirmMsg.append("现在回到之前的【").append(resumed.getIntentName()).append("】业务。\n");

            // 展示已收集的槽位
            if (!resumed.getCollectedSlots().isEmpty()) {
                confirmMsg.append("已有信息：");
                for (Map.Entry<String, String> entry : resumed.getCollectedSlots().entrySet()) {
                    SlotDefinition def = resumed.getSlotSchema().get(entry.getKey());
                    if (def != null) {
                        confirmMsg.append(def.getDisplayName()).append(": ")
                                  .append(entry.getValue()).append("、");
                    }
                }
                confirmMsg.append("\n");
            }

            // 追问剩余槽位
            List<SlotDefinition> missing = resumed.getMissingRequiredSlots();
            if (!missing.isEmpty()) {
                confirmMsg.append(missing.get(0).getPrompt());
            } else {
                // 被挂起的帧也收集完了（极端情况）
                confirmMsg.append("之前的业务信息也已收集完毕，正在为您处理...");
            }
        }

        String response = confirmMsg.toString();
        ctx.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, response));
        return Flux.just(response);
    }

    // ==================== 问答处理 ====================

    private Flux<String> handleQA(SessionContext ctx, LlmParsedResult parsed, String userInput) {
        Flux<String> responseFlux;

        if (parsed.isNeedWebSearch() && parsed.getSearchQuery() != null) {
            responseFlux = deepSeekService.streamWithSearchContext(
                    userInput, parsed.getSearchQuery(), ctx.getRecentHistory(5));
        } else if (parsed.getQaAnswer() != null && !parsed.getQaAnswer().isBlank()) {
            ctx.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, parsed.getQaAnswer()));
            return Flux.just(parsed.getQaAnswer());
        } else {
            responseFlux = deepSeekService.streamChat(userInput, ctx.getRecentHistory(5), null);
        }

        StringBuilder fullResponse = new StringBuilder();
        return responseFlux
                .doOnNext(fullResponse::append)
                .doOnComplete(() ->
                    ctx.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, fullResponse.toString()))
                );
    }

    // ==================== 工具方法 ====================

    private boolean isBusinessIntent(String intent) {
        return intent != null && intentRegistry.getIntent(intent) != null;
    }

    private String buildStackContext(SessionContext ctx) {
        if (!ctx.hasActiveFrame()) return null;

        DialogFrame frame = ctx.currentFrame();
        StringBuilder sb = new StringBuilder();
        sb.append("当前正在办理: ").append(frame.getIntentName())
          .append(" (意图ID: ").append(frame.getIntentId()).append(")\n");
        sb.append("已收集槽位: ").append(frame.getCollectedSlots()).append("\n");

        List<SlotDefinition> missing = frame.getMissingRequiredSlots();
        if (!missing.isEmpty()) {
            sb.append("待收集槽位: ");
            for (SlotDefinition slot : missing) {
                sb.append(slot.getName()).append("[").append(slot.getDisplayName()).append("] ");
            }
            sb.append("\n");
            sb.append("重要: 如果用户明确说要办理其他业务(如办信用卡、查余额等)，请正确识别为对应的业务intent，而不是none。\n");
            sb.append("只有当用户在直接回答当前槽位问题时(如回答名字、金额)，才设intent=none并在slots中填入对应值。");
        }

        return sb.toString();
    }
}

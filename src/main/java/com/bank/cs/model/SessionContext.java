package com.bank.cs.model;

import java.util.*;

/**
 * 会话上下文：包含对话栈 + 对话历史 + 用户实体信息 + 意图跳转确认状态
 */
public class SessionContext {

    private String sessionId;
    private Deque<DialogFrame> dialogStack;     // 对话栈
    private List<ChatMessage> chatHistory;       // 对话历史
    private Map<String, String> userEntities;    // 跨意图共享的实体(如身份信息)
    private long lastActiveTime;

    // --- 意图跳转待确认 ---
    private String pendingSwitchIntentId;         // 等待用户确认跳转的目标意图
    private Map<String, String> pendingSwitchSlots; // 目标意图已提取到的槽位

    public SessionContext(String sessionId) {
        this.sessionId = sessionId;
        this.dialogStack = new ArrayDeque<>();
        this.chatHistory = new ArrayList<>();
        this.userEntities = new LinkedHashMap<>();
        this.lastActiveTime = System.currentTimeMillis();
    }

    public void touch() {
        this.lastActiveTime = System.currentTimeMillis();
    }

    // --- 意图跳转确认相关 ---

    public boolean hasPendingSwitch() {
        return pendingSwitchIntentId != null;
    }

    public void setPendingSwitch(String intentId, Map<String, String> slots) {
        this.pendingSwitchIntentId = intentId;
        this.pendingSwitchSlots = slots;
    }

    public void clearPendingSwitch() {
        this.pendingSwitchIntentId = null;
        this.pendingSwitchSlots = null;
    }

    public String getPendingSwitchIntentId() { return pendingSwitchIntentId; }
    public Map<String, String> getPendingSwitchSlots() { return pendingSwitchSlots; }

    // --- 对话栈操作 ---

    /** 推入新意图帧，挂起当前帧 */
    public void pushFrame(DialogFrame frame) {
        if (!dialogStack.isEmpty()) {
            DialogFrame current = dialogStack.peek();
            current.setState(DialogFrame.State.SUSPENDED);
            current.setSuspendReason("用户插入新意图: " + frame.getIntentId());
        }
        dialogStack.push(frame);
    }

    /** 弹出当前已完成的帧，恢复上一帧 */
    public DialogFrame popAndResume() {
        if (dialogStack.isEmpty()) return null;
        DialogFrame completed = dialogStack.pop();
        completed.setState(DialogFrame.State.DONE);
        if (!dialogStack.isEmpty()) {
            DialogFrame resumed = dialogStack.peek();
            resumed.setState(DialogFrame.State.COLLECTING);
            resumed.setSuspendReason(null);
            return resumed;
        }
        return null;
    }

    /** 获取栈顶活跃帧 */
    public DialogFrame currentFrame() {
        return dialogStack.peek();
    }

    public boolean hasActiveFrame() {
        return !dialogStack.isEmpty();
    }

    public void addMessage(ChatMessage msg) {
        chatHistory.add(msg);
    }

    /** 获取最近N轮对话用于LLM上下文 */
    public List<ChatMessage> getRecentHistory(int maxTurns) {
        int start = Math.max(0, chatHistory.size() - maxTurns * 2);
        return chatHistory.subList(start, chatHistory.size());
    }

    // --- Getters ---
    public String getSessionId() { return sessionId; }
    public Deque<DialogFrame> getDialogStack() { return dialogStack; }
    public List<ChatMessage> getChatHistory() { return chatHistory; }
    public Map<String, String> getUserEntities() { return userEntities; }
    public long getLastActiveTime() { return lastActiveTime; }
}

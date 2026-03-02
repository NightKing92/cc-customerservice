package com.bank.cs.model;

import java.util.*;

/**
 * 对话栈中的一帧，代表一个正在进行的意图/业务
 */
public class DialogFrame {

    public enum State {
        COLLECTING,  // 正在收集槽位
        CONFIRMING,  // 等待用户确认
        EXECUTING,   // 正在执行业务
        SUSPENDED,   // 被新意图挂起
        DONE         // 已完成
    }

    private String intentId;
    private String intentName;
    private Map<String, SlotDefinition> slotSchema;  // 槽位定义
    private Map<String, String> collectedSlots;       // 已收集的槽位值
    private State state;
    private String suspendReason;
    private long createdAt;

    public DialogFrame(String intentId, String intentName, Map<String, SlotDefinition> slotSchema) {
        this.intentId = intentId;
        this.intentName = intentName;
        this.slotSchema = slotSchema != null ? slotSchema : new LinkedHashMap<>();
        this.collectedSlots = new LinkedHashMap<>();
        this.state = State.COLLECTING;
        this.createdAt = System.currentTimeMillis();
    }

    /** 填充槽位（带校验），返回是否有新值被填入 */
    public boolean fillSlots(Map<String, String> extracted) {
        if (extracted == null) return false;
        boolean changed = false;
        lastValidationError = null;
        for (Map.Entry<String, String> entry : extracted.entrySet()) {
            if (slotSchema.containsKey(entry.getKey()) && entry.getValue() != null && !entry.getValue().isBlank()) {
                SlotDefinition def = slotSchema.get(entry.getKey());
                if (!def.validate(entry.getValue())) {
                    // 校验失败，记录错误但不填入
                    lastValidationError = def.getValidationHint() != null
                            ? def.getValidationHint()
                            : def.getDisplayName() + "格式不正确，请重新输入。";
                    continue;
                }
                String old = collectedSlots.put(entry.getKey(), entry.getValue());
                if (!entry.getValue().equals(old)) changed = true;
            }
        }
        return changed;
    }

    private String lastValidationError;

    public String getLastValidationError() { return lastValidationError; }
    public void clearValidationError() { lastValidationError = null; }

    /** 获取所有未填充的必填槽位 */
    public List<SlotDefinition> getMissingRequiredSlots() {
        List<SlotDefinition> missing = new ArrayList<>();
        for (Map.Entry<String, SlotDefinition> entry : slotSchema.entrySet()) {
            if (entry.getValue().isRequired() && !collectedSlots.containsKey(entry.getKey())) {
                missing.add(entry.getValue());
            }
        }
        return missing;
    }

    /** 所有必填槽位是否已收集完毕 */
    public boolean allRequiredSlotsFilled() {
        return getMissingRequiredSlots().isEmpty();
    }

    // --- Getters & Setters ---
    public String getIntentId() { return intentId; }
    public String getIntentName() { return intentName; }
    public Map<String, SlotDefinition> getSlotSchema() { return slotSchema; }
    public Map<String, String> getCollectedSlots() { return collectedSlots; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public String getSuspendReason() { return suspendReason; }
    public void setSuspendReason(String reason) { this.suspendReason = reason; }
    public long getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "DialogFrame{intent=" + intentId + ", state=" + state +
                ", slots=" + collectedSlots + "/" + slotSchema.keySet() + "}";
    }
}

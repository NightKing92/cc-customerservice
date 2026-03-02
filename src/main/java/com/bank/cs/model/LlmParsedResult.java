package com.bank.cs.model;

import java.util.Map;

/**
 * LLM意图识别+槽位提取的结构化结果
 */
public class LlmParsedResult {

    private String intent;                // 识别到的意图: "qa" / "transfer" / "none"
    private Map<String, String> slots;    // 提取到的槽位值
    private String qaAnswer;              // 如果是问答意图，直接给出回答
    private boolean needWebSearch;        // 是否需要联网搜索
    private String searchQuery;           // 搜索关键词

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public Map<String, String> getSlots() { return slots; }
    public void setSlots(Map<String, String> slots) { this.slots = slots; }
    public String getQaAnswer() { return qaAnswer; }
    public void setQaAnswer(String qaAnswer) { this.qaAnswer = qaAnswer; }
    public boolean isNeedWebSearch() { return needWebSearch; }
    public void setNeedWebSearch(boolean needWebSearch) { this.needWebSearch = needWebSearch; }
    public String getSearchQuery() { return searchQuery; }
    public void setSearchQuery(String searchQuery) { this.searchQuery = searchQuery; }
}

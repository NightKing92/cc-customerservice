package com.bank.cs.model;

/**
 * 槽位定义
 */
public class SlotDefinition {

    private String name;           // 槽位标识 e.g. "payee"
    private String displayName;    // 展示名 e.g. "收款人"
    private String prompt;         // 追问话术 e.g. "请问您要转给谁？"
    private boolean required;
    private String validationRegex; // 校验正则，null表示不校验
    private String validationHint;  // 校验失败提示

    public SlotDefinition() {}

    public SlotDefinition(String name, String displayName, String prompt, boolean required) {
        this(name, displayName, prompt, required, null, null);
    }

    public SlotDefinition(String name, String displayName, String prompt, boolean required,
                         String validationRegex, String validationHint) {
        this.name = name;
        this.displayName = displayName;
        this.prompt = prompt;
        this.required = required;
        this.validationRegex = validationRegex;
        this.validationHint = validationHint;
    }

    /** 校验槽位值是否合法 */
    public boolean validate(String value) {
        if (value == null || value.isBlank()) return false;
        if (validationRegex == null) return true;
        return value.matches(validationRegex);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public String getValidationRegex() { return validationRegex; }
    public void setValidationRegex(String validationRegex) { this.validationRegex = validationRegex; }
    public String getValidationHint() { return validationHint; }
    public void setValidationHint(String validationHint) { this.validationHint = validationHint; }
}

package io.quack.client.ui;

/**
 * @ 提及的解析逻辑（抽出来便于单测）。
 *
 * <p>坑：输入 `@` 的瞬间，文本框的 textProperty 监听器可能比光标更新更早触发，
 * 此时 caret 还停在 `@` 前面。原来的实现用 {@code lastIndexOf('@', caret - 1)}，
 * 再减一就永远搜不到；而 `substring(at + 1, caret)` 在 at == caret 时还会抛异常。
 */
public final class MentionParser {

    /** 解析结果：@ 的位置 + @ 后面的查询词 */
    public record Token(int start, String query) {
    }

    private MentionParser() {
    }

    /**
     * 找出光标处正在输入的 @ 片段。
     *
     * @param text  输入框全文
     * @param caret 光标位置（可能还没跟上最后一个字符）
     * @return 没有正在输入的 @ 返回 null
     */
    public static Token tokenAt(String text, int caret) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int end = Math.max(0, Math.min(caret, text.length()));
        int limit = Math.min(end, text.length() - 1);
        if (limit < 0) {
            return null;
        }
        int at = text.lastIndexOf('@', limit);
        if (at < 0 || at > end) {
            return null;
        }
        // 光标可能还停在 @ 前面（end == at），此时查询词为空，不能 substring 反了
        String query = end > at + 1 ? text.substring(at + 1, end) : "";
        if (query.indexOf(' ') >= 0 || query.indexOf('\n') >= 0 || query.length() > 12) {
            return null;
        }
        return new Token(at, query);
    }
}

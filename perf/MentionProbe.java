import io.quack.client.ui.MentionParser;

/** @ 提及解析：刚按下 @（光标还没跟上）也必须立刻识别 */
public class MentionProbe {

    private static int failures;

    public static void main(String[] args) {
        check("@ 且光标已在 @ 后", "@", 1, 0, "");
        check("@ 且光标还在 @ 前（textProperty 早于光标更新）", "@", 0, 0, "");
        check("中文后输入 @", "你好@", 3, 2, "");
        check("继续输入过滤词", "@小", 2, 0, "小");
        check("@所有人", "@所有人", 4, 0, "所有人");
        check("空格后不再提示", "@小 鲍", 4, -1, null);
        check("没有 @", "hello", 5, -1, null);
        check("@ 在中间、光标紧跟在字母后", "hi @a and", 5, 3, "a");
        check("@ 后面已经有空格就不再提示", "hi @a and", 6, -1, null);

        System.out.println(failures == 0 ? "MENTION OK" : "MENTION FAILED: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String label, String text, int caret, int expectedStart,
                              String expectedQuery) {
        MentionParser.Token token = MentionParser.tokenAt(text, caret);
        boolean ok;
        if (expectedStart < 0) {
            ok = token == null;
        } else {
            ok = token != null && token.start() == expectedStart
                    && expectedQuery.equals(token.query());
        }
        if (!ok) {
            failures++;
        }
        System.out.printf("%s %s（text=\"%s\" caret=%d -> %s）%n",
                ok ? "PASS" : "FAIL", label, text, caret,
                token == null ? "null" : "start=" + token.start() + ", query=\"" + token.query() + "\"");
    }
}

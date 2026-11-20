import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 拼音首字母分组验证（不依赖 JavaFX） */
public class PinyinProbe {

    public static void main(String[] args) {
        String[] names = {"张三", "李四", "王五", "Alice", "bob", "赵六", "123", ""};
        List<String[]> rows = new ArrayList<>();
        for (String name : names) {
            rows.add(new String[]{initialOf(name), name});
        }
        rows.sort(Comparator.comparing((String[] r) -> r[0]).thenComparing(r -> r[1]));
        System.out.println("-- 按首字母排序 --");
        for (String[] row : rows) {
            System.out.println("  " + row[0] + "  " + row[1]);
        }
        boolean ok = "Z".equals(initialOf("张三")) && "L".equals(initialOf("李四"))
                && "W".equals(initialOf("王五")) && "A".equals(initialOf("Alice"))
                && "B".equals(initialOf("bob")) && "Z".equals(initialOf("赵六"))
                && "#".equals(initialOf("123")) && "#".equals(initialOf(""));
        System.out.println(ok ? "PINYIN OK" : "PINYIN FAILED");
        System.exit(ok ? 0 : 1);
    }

    private static String initialOf(String name) {
        if (name == null || name.isBlank()) {
            return "#";
        }
        char first = name.trim().charAt(0);
        if (first >= 'a' && first <= 'z') {
            return String.valueOf(Character.toUpperCase(first));
        }
        if (first >= 'A' && first <= 'Z') {
            return String.valueOf(first);
        }
        try {
            String[] pinyin = net.sourceforge.pinyin4j.PinyinHelper.toHanyuPinyinStringArray(first);
            if (pinyin != null && pinyin.length > 0 && !pinyin[0].isEmpty()) {
                return String.valueOf(Character.toUpperCase(pinyin[0].charAt(0)));
            }
        } catch (Exception ignored) {
        }
        return "#";
    }
}

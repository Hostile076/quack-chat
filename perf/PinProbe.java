import io.quack.client.store.ClientStore;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.ChatType;
import io.quack.common.model.Friend;
import io.quack.common.model.User;

import java.util.ArrayList;
import java.util.List;

/** 只测置顶排序，打印细节 */
public class PinProbe {

    public static void main(String[] args) {
        ClientStore store = new ClientStore();
        List<Friend> friends = new ArrayList<>();
        friends.add(new Friend("10002", "B", "B", true));
        friends.add(new Friend("10003", "C", "C", true));
        friends.add(new Friend("10004", "A", "A", true));
        store.reset(new LoginResp(new User(10001L, "alice", "Alice", null), friends,
                new ArrayList<ChatGroup>()));

        System.out.println("-- 初始顺序 --");
        store.conversations().forEach(c -> System.out.println(
                "  " + c.getTitle() + " key=" + c.key() + " pinned=" + store.prefs().isPinned(c.key())));

        String key = "PRIVATE:10003";
        System.out.println("设置置顶 key=" + key);
        store.prefs().setPinned(key, true);
        System.out.println("isPinned(10003)=" + store.prefs().isPinned(key));
        store.resort();

        System.out.println("-- 排序后 --");
        store.conversations().forEach(c -> System.out.println(
                "  " + c.getTitle() + " pinned=" + store.prefs().isPinned(c.key())));
        boolean ok = "C".equals(store.conversations().get(0).getTitle());
        System.out.println(ok ? "PIN OK" : "PIN FAILED");
    }
}

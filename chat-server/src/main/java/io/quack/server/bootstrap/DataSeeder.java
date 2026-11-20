package io.quack.server.bootstrap;

import io.quack.common.model.ChatGroup;
import io.quack.common.model.User;
import io.quack.server.service.FriendService;
import io.quack.server.service.GroupService;
import io.quack.server.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 演示数据。
 *
 * <p>一共 <b>15 个演示账号</b>，用户名/昵称统一为「演示账号1 … 演示账号15」，
 * QC 号从 10001 依次分配到 10015，密码都是 {@code 123456}。
 * 演示时随便挑一个号登录即可，任意两个演示账号之间已经是好友（可以直接私聊）。
 *
 * <p>群：
 * <ul>
 *   <li>{@code q10001} 摸鱼小分队 —— 15 个演示账号全部在群里，群主是演示账号1；</li>
 *   <li>{@code q10002} Java 自学小组 —— 演示账号1~8，群主是演示账号2。</li>
 * </ul>
 *
 * <p>接上 MySQL 之后这个类应该整体删掉，换成初始化 SQL。
 */
@Component
public class DataSeeder implements ApplicationRunner {

    /** 演示账号数量 */
    public static final int DEMO_COUNT = 15;
    /** 演示账号统一密码 */
    public static final String DEMO_PASSWORD = "123456";
    /** 群 2 的成员数量（演示账号1~8） */
    private static final int SECOND_GROUP_SIZE = 8;

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserService userService;
    private final FriendService friendService;
    private final GroupService groupService;

    public DataSeeder(UserService userService, FriendService friendService, GroupService groupService) {
        this.userService = userService;
        this.friendService = friendService;
        this.groupService = groupService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userService.count() > 0) {
            return;
        }

        // 1. 15 个演示账号：QC 10001 ~ 10015，用户名和昵称都用「演示账号i」
        List<User> demos = new ArrayList<>(DEMO_COUNT);
        for (int i = 1; i <= DEMO_COUNT; i++) {
            String name = "演示账号" + i;
            demos.add(userService.register(name, DEMO_PASSWORD, name));
        }

        // 2. 两两互加好友：任意两个演示账号都能直接私聊
        for (int i = 0; i < demos.size(); i++) {
            for (int j = i + 1; j < demos.size(); j++) {
                User a = demos.get(i);
                User b = demos.get(j);
                friendService.link(a.id(), b.id(), b.displayName(), a.displayName());
            }
        }

        // 3. 群 1：15 人全部在群里，群主是演示账号1
        List<String> allButOwner = new ArrayList<>();
        for (int i = 1; i < demos.size(); i++) {
            allButOwner.add(demos.get(i).id());
        }
        ChatGroup first = groupService.create("摸鱼小分队", demos.get(0).id(), allButOwner);
        groupService.update(first.id(), null, "本周五晚八点，一起过一遍服务端代码。");

        // 4. 群 2：演示账号1~8，群主是演示账号2
        List<String> secondMembers = new ArrayList<>();
        for (int i = 0; i < SECOND_GROUP_SIZE; i++) {
            if (i != 1) {
                secondMembers.add(demos.get(i).id());
            }
        }
        ChatGroup second = groupService.create("Java 自学小组", demos.get(1).id(), secondMembers);

        log.info("演示数据初始化完成：{} 个演示账号（QC 10001~{}，用户名「演示账号1…{}」，密码 {}），"
                        + "群 {} {} 人 / 群 {} {} 人",
                DEMO_COUNT, demos.get(demos.size() - 1).qcId(), DEMO_COUNT, DEMO_PASSWORD,
                first.id(), first.memberCount(), second.id(), second.memberCount());
    }
}

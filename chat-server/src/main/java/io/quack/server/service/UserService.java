package io.quack.server.service;

import io.quack.common.dto.UpdateProfileReq;
import io.quack.common.model.User;
import io.quack.common.model.UserProfile;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 用户表的内存实现。
 *
 * <p>两个关键规则：
 * <ol>
 *   <li><b>QC 号唯一且连续</b>：从 10001 开始颁发，10001 是内置测试号；</li>
 *   <li><b>用户名可以重复</b>（像 QQ 昵称一样），所以登录和加好友都以 QC 号为主标识，
 *       用户名只作为搜索条件。重名用户用用户名登录时会被要求改用 QC 号。</li>
 * </ol>
 *
 * <p>密码存储仍是教学简化版（SHA-256 + 固定盐），生产必须换 BCrypt。
 */
@Service
public class UserService {

    public static final long FIRST_QC_ID = 10001L;

    private static final String SALT = "quack:";

    private final Map<Long, Account> accounts = new ConcurrentHashMap<>();
    /** 用户名 -> 多个账号（允许重名） */
    private final Map<String, List<Account>> byUsername = new ConcurrentHashMap<>();
    private final Map<Long, UserProfile> profiles = new ConcurrentHashMap<>();
    private final AtomicLong qcSequence = new AtomicLong(FIRST_QC_ID);
    private static final Pattern BIRTHDAY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** 内部账户，passwordHash 永不外泄 */
    private record Account(User user, String passwordHash) {
    }

    /**
     * 注册：分配下一个 QC 号。
     *
     * @return 新用户（含 QC 号）
     */
    public synchronized User register(String username, String rawPassword, String nickname) {
        String name = username.trim();
        if (name.isEmpty()) {
            throw BizException.badRequest("用户名不能为空");
        }
        long qcId = qcSequence.getAndIncrement();
        String displayName = nickname == null || nickname.isBlank() ? name : nickname.trim();
        User user = new User(qcId, name, displayName, null);
        Account account = new Account(user, hashCode(rawPassword));
        accounts.put(qcId, account);
        byUsername.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(account);
        profiles.put(qcId, new UserProfile(qcId, name, displayName, "", "保密", "", "", "", 0,
                System.currentTimeMillis()));
        return user;
    }

    // ------------------------------------------------------------------
    // 资料
    // ------------------------------------------------------------------

    /** 不传 id 表示看自己 */
    public Optional<UserProfile> profile(String id) {
        return byId(id).flatMap(u -> Optional.ofNullable(profiles.get(u.qcId())));
    }

    /**
     * 修改自己的资料。字段传 null 表示“不改”，传空串表示“清空”。
     */
    public synchronized UserProfile updateProfile(String id, UpdateProfileReq req) {
        User current = byId(id).orElseThrow(() -> BizException.unauthorized("账号状态异常"));
        UserProfile old = profiles.computeIfAbsent(current.qcId(), qc -> new UserProfile(qc,
                current.username(), current.nickname(), current.avatar(), "", "保密", "", "", "", 0,
                System.currentTimeMillis()));

        // 用户名可以改，但要保证不超过 20 字、不能空
        String username = req.username() == null ? old.username() : req.username().trim();
        if (username.isEmpty()) {
            username = old.username();
        }
        if (username.length() > 20) {
            throw BizException.badRequest("用户名不能超过 20 个字");
        }

        String nickname = req.nickname() == null ? old.nickname() : req.nickname().trim();
        if (nickname.isEmpty()) {
            nickname = old.nickname();
        }
        // 昵称默认跟随用户名：没单独设置过昵称（等于旧用户名）时，改用户名要一起变
        if (req.nickname() == null && old.nickname().equals(old.username())) {
            nickname = username;
        }
        if (nickname.length() > 20) {
            throw BizException.badRequest("昵称不能超过 20 个字");
        }
        String signature = req.signature() == null ? old.signature() : req.signature().trim();
        if (signature.length() > 60) {
            throw BizException.badRequest("个性签名不能超过 60 个字");
        }
        String birthday = req.birthday() == null ? old.birthday() : req.birthday().trim();
        if (!birthday.isEmpty() && !BIRTHDAY.matcher(birthday).matches()) {
            throw BizException.badRequest("生日格式应为 yyyy-MM-dd");
        }

        String avatar = req.avatar() == null ? old.avatar() : req.avatar().trim();
        UserProfile updated = new UserProfile(current.qcId(), username, nickname, avatar,
                signature,
                blankTo(req.gender(), old.gender(), "保密"),
                birthday,
                blankTo(req.region(), old.region(), ""),
                blankTo(req.identity(), old.identity(), ""),
                old.likes(),
                old.createdAt());
        profiles.put(current.qcId(), updated);

        // 昵称 / 用户名 / 头像变了要同步到 User，好友列表、消息里才会更新
        if (!nickname.equals(current.nickname())
                || !username.equals(current.username())
                || !java.util.Objects.equals(avatar, current.avatar())) {
            User renamed = new User(current.qcId(), username, nickname, avatar);
            Account account = accounts.get(current.qcId());
            if (account != null) {
                accounts.put(current.qcId(), new Account(renamed, account.passwordHash()));
            }
            List<Account> list = byUsername.get(current.username().toLowerCase());
            if (list != null) {
                list.replaceAll(a -> a.user().qcId() == current.qcId()
                        ? new Account(renamed, a.passwordHash()) : a);
            }
            List<Account> newList = byUsername.computeIfAbsent(username.toLowerCase(),
                    key -> new java.util.concurrent.CopyOnWriteArrayList<>());
            if (list != newList) {
                if (list != null) {
                    list.removeIf(a -> a.user().qcId() == current.qcId());
                }
                newList.add(new Account(renamed, account == null ? "" : account.passwordHash()));
            }
        }
        return updated;
    }

    /** 点赞：不能给自己点，重复点也累加（真实产品会做去重，这里保持简单） */
    public synchronized int like(String id) {
        User target = byId(id).orElseThrow(() -> new BizException(404, "用户不存在"));
        UserProfile profile = profiles.get(target.qcId());
        if (profile == null) {
            throw new BizException(404, "用户资料不存在");
        }
        // 注意用 11 参构造器带上 avatar：10 参兼容构造器会把头像写成 null，
        // 结果就是「给谁点个赞，对方头像就没了」。
        UserProfile updated = new UserProfile(profile.qcId(), profile.username(), profile.nickname(),
                profile.avatar(), profile.signature(), profile.gender(), profile.birthday(),
                profile.region(), profile.identity(), profile.likes() + 1, profile.createdAt());
        profiles.put(target.qcId(), updated);
        return updated.likes();
    }

    private static String blankTo(String value, String fallback, String emptyValue) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? emptyValue : trimmed;
    }

    /**
     * 登录。
     *
     * @param account QC 号（纯数字）或用户名
     */
    public Optional<User> authenticate(String account, String rawPassword) {
        if (account == null || rawPassword == null) {
            return Optional.empty();
        }
        String trimmed = account.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        if (isNumeric(trimmed)) {
            Account account0 = accounts.get(Long.parseLong(trimmed));
            return account0 != null && account0.passwordHash().equals(hashCode(rawPassword))
                    ? Optional.of(account0.user()) : Optional.empty();
        }

        List<Account> candidates = byUsername.getOrDefault(trimmed.toLowerCase(), List.of());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() > 1) {
            throw new BizException(409, "用户名「" + trimmed + "」存在多个账号，请使用 QC 号登录");
        }
        Account single = candidates.get(0);
        return single.passwordHash().equals(hashCode(rawPassword)) ? Optional.of(single.user()) : Optional.empty();
    }

    /**
     * 搜索用户。
     *
     * <p>规则（对应需求）：
     * <ul>
     *   <li>关键字是纯数字：先精确匹配 QC 号，命中就排第一；再匹配“同名”的用户名（比如有人把用户名起成 10001）；</li>
     *   <li>关键字不是数字：按用户名匹配（精确优先，其次包含）；</li>
     *   <li>结果一律按 QC 号升序，并排除自己。</li>
     * </ul>
     */
    public List<User> search(String keyword, String selfId, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String trimmed = keyword.trim();
        int max = Math.max(1, Math.min(limit, 50));

        Set<User> ordered = new LinkedHashSet<>();
        if (isNumeric(trimmed)) {
            // QC 号优先于“用户名刚好等于这个数字”的账号
            Account exact = accounts.get(Long.parseLong(trimmed));
            if (exact != null) {
                ordered.add(exact.user());
            }
            byUsername.getOrDefault(trimmed.toLowerCase(), List.of()).stream()
                    .sorted(Comparator.comparingLong(a -> a.user().qcId()))
                    .forEach(a -> ordered.add(a.user()));
        } else {
            List<Account> matched = new ArrayList<>();
            for (List<Account> accounts : byUsername.values()) {
                for (Account account : accounts) {
                    if (account.user().username().toLowerCase().contains(trimmed.toLowerCase())) {
                        matched.add(account);
                    }
                }
            }
            matched.sort(Comparator
                    .comparing((Account a) -> a.user().username().equalsIgnoreCase(trimmed) ? 0 : 1)
                    .thenComparingLong(a -> a.user().qcId()));
            matched.forEach(a -> ordered.add(a.user()));
        }

        return ordered.stream()
                .filter(u -> !u.id().equals(selfId))
                .limit(max)
                .toList();
    }

    public Optional<User> byId(String id) {
        if (id == null || !isNumeric(id.trim())) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(accounts.get(Long.parseLong(id.trim()))).map(Account::user);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Optional<User> byQcId(long qcId) {
        return Optional.ofNullable(accounts.get(qcId)).map(Account::user);
    }

    public String nicknameOf(String userId) {
        return byId(userId).map(User::nickname).orElse(userId);
    }

    // ------------------------------------------------------------------
    // 持久化：快照 / 恢复
    // ------------------------------------------------------------------

    public java.util.List<io.quack.server.store.PersistedState.AccountState> snapshotAccounts() {
        return accounts.values().stream()
                .map(account -> new io.quack.server.store.PersistedState.AccountState(
                        account.user(), account.passwordHash()))
                .toList();
    }

    public synchronized void restoreAccounts(
            java.util.List<io.quack.server.store.PersistedState.AccountState> states) {
        if (states == null) {
            return;
        }
        accounts.clear();
        byUsername.clear();
        for (io.quack.server.store.PersistedState.AccountState state : states) {
            if (state == null || state.user() == null) {
                continue;
            }
            Account account = new Account(state.user(), state.passwordHash());
            accounts.put(account.user().qcId(), account);
            // 索引 key 必须和 register/authenticate/updateProfile 一致用小写，
            // 否则大小写混合的用户名重启后就登录不了了。
            byUsername.computeIfAbsent(account.user().username().toLowerCase(),
                            key -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(account);
            qcSequence.set(Math.max(qcSequence.get(), account.user().qcId()));
        }
    }

    public java.util.List<UserProfile> snapshotProfiles() {
        return new java.util.ArrayList<>(profiles.values());
    }

    public void restoreProfiles(java.util.List<UserProfile> list) {
        if (list == null) {
            return;
        }
        profiles.clear();
        for (UserProfile profile : list) {
            if (profile != null) {
                profiles.put(profile.qcId(), profile);
            }
        }
    }

    public long qcSequenceNow() {
        return qcSequence.get();
    }

    public void restoreQcSequence(long value) {
        if (value > 0) {
            qcSequence.set(Math.max(qcSequence.get(), value));
        }
    }

    public int count() {
        return accounts.size();
    }

    private static boolean isNumeric(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (text.length() > 18) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String hashCode(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((SALT + raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}

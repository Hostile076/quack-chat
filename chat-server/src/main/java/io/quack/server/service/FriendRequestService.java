package io.quack.server.service;

import io.quack.common.model.FriendRequest;
import io.quack.common.model.User;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 好友申请。
 *
 * <p>流程：A 搜索到 B → 发申请（B 收到推送）→ B 在“新的朋友”里同意/拒绝 →
 * 同意后双方建立好友关系，A 收到 {@code friend.added} 推送。
 *
 * <p>状态机很简单，但要注意幂等：重复申请、已互为好友、给自己发申请都要挡住。
 */
@Service
public class FriendRequestService {

    private final Map<String, FriendRequest> requests = new ConcurrentHashMap<>();
    private final FriendService friendService;

    public FriendRequestService(FriendService friendService) {
        this.friendService = friendService;
    }

    public synchronized FriendRequest create(User from, String toId, String message) {
        return create(from, toId, message, null);
    }

    public synchronized FriendRequest create(User from, String toId, String message, String remark) {
        if (from.id().equals(toId)) {
            throw BizException.badRequest("不能添加自己为好友");
        }
        if (friendService.areFriends(from.id(), toId)) {
            throw BizException.badRequest("你们已经是好友了");
        }
        if (hasPending(from.id(), toId)) {
            throw new BizException(409, "已经发过申请了，等对方处理吧");
        }
        FriendRequest request = new FriendRequest(
                UUID.randomUUID().toString(),
                from.id(),
                from.displayName(),
                toId,
                message == null ? "" : message.trim(),
                System.currentTimeMillis(),
                FriendRequest.PENDING,
                remark == null ? null : remark.trim(),
                from.avatar());
        requests.put(request.id(), request);
        return request;
    }

    /** 别人申请加我（待处理），新的在前 */
    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    public List<FriendRequest> snapshotRequests() {
        return new java.util.ArrayList<>(requests.values());
    }

    public void restoreRequests(List<FriendRequest> list) {
        if (list == null) {
            return;
        }
        requests.clear();
        for (FriendRequest request : list) {
            if (request != null && request.id() != null) {
                requests.put(request.id(), request);
            }
        }
    }

    public List<FriendRequest> incoming(String userId) {
        return requests.values().stream()
                .filter(r -> userId.equals(r.toId()) && r.isPending())
                .sorted(Comparator.comparingLong(FriendRequest::ts).reversed())
                .toList();
    }

    /** 我发出去的申请，新的在前 */
    public List<FriendRequest> outgoing(String userId) {
        return requests.values().stream()
                .filter(r -> userId.equals(r.fromId()) && r.isPending())
                .sorted(Comparator.comparingLong(FriendRequest::ts).reversed())
                .toList();
    }

    /** 取出申请并校验“这条申请确实是发给我的” */
    public FriendRequest requireForTarget(String requestId, String userId) {
        FriendRequest request = requests.get(requestId);
        if (request == null) {
            throw new BizException(404, "申请不存在或已被处理");
        }
        if (!userId.equals(request.toId())) {
            throw BizException.forbidden("这条申请不是发给你的");
        }
        if (!request.isPending()) {
            throw BizException.badRequest("这条申请已经处理过了");
        }
        return request;
    }

    public FriendRequest finish(FriendRequest request, boolean accept) {
        FriendRequest updated = new FriendRequest(request.id(), request.fromId(), request.fromName(),
                request.toId(), request.message(), request.ts(),
                accept ? FriendRequest.ACCEPTED : FriendRequest.REJECTED,
                request.remark(), request.fromAvatar());
        requests.put(updated.id(), updated);
        return updated;
    }

    public boolean hasPending(String fromId, String toId) {
        return requests.values().stream()
                .anyMatch(r -> r.isPending() && fromId.equals(r.fromId()) && toId.equals(r.toId()));
    }
}

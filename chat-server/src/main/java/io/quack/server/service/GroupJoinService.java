package io.quack.server.service;

import io.quack.common.model.GroupJoinRequest;
import io.quack.common.model.User;
import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 入群审批：申请、待处理列表、同意/拒绝 */
@Service
public class GroupJoinService {

    private final Map<String, GroupJoinRequest> requests = new ConcurrentHashMap<>();
    private final GroupService groupService;
    private final PushService pushService;

    public GroupJoinService(GroupService groupService, PushService pushService) {
        this.groupService = groupService;
        this.pushService = pushService;
    }

    /** 提交入群申请；已有 PENDING 申请直接返回旧的 */
    public synchronized GroupJoinRequest request(String groupId, User user) {
        if (groupService.byId(groupId).isEmpty()) {
            throw new BizException(404, "群不存在");
        }
        if (groupService.contains(groupId, user.id())) {
            throw BizException.badRequest("你已经在群里");
        }
        GroupJoinRequest existing = requests.values().stream()
                .filter(r -> r.pending() && r.groupId().equals(groupId)
                        && r.userId().equals(user.id()))
                .findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }
        String groupName = groupService.byId(groupId).map(io.quack.common.model.ChatGroup::name).orElse("");
        GroupJoinRequest request = new GroupJoinRequest(UUID.randomUUID().toString(), groupId,
                groupName, user.id(), user.displayName(), user.avatar(),
                GroupJoinRequest.PENDING, System.currentTimeMillis(), null, 0);
        requests.put(request.id(), request);
        // 推给群主和所有管理员
        groupService.membersOf(groupId).stream()
                .filter(member -> groupService.isManager(groupId, member))
                .forEach(manager -> pushService.forwardTransient(manager,
                        Envelope.of(Cmd.GROUP_JOIN_REQUEST_PUSH, 0, request)));
        return request;
    }

    public synchronized GroupJoinRequest handle(String requestId, String handlerId, boolean accept) {
        GroupJoinRequest request = requests.get(requestId);
        if (request == null) {
            throw new BizException(404, "申请不存在");
        }
        if (!request.pending()) {
            throw BizException.badRequest("这条申请已经处理过了");
        }
        if (!groupService.isManager(request.groupId(), handlerId)) {
            throw BizException.forbidden("只有群主或管理员可以处理入群申请");
        }
        GroupJoinRequest updated = new GroupJoinRequest(request.id(), request.groupId(),
                request.groupName(), request.userId(), request.userName(), request.userAvatar(),
                accept ? GroupJoinRequest.APPROVED : GroupJoinRequest.REJECTED,
                request.ts(), handlerId, System.currentTimeMillis());
        requests.put(updated.id(), updated);
        if (accept) {
            if (groupService.byId(updated.groupId()).isPresent()) {
                groupService.join(updated.groupId(), updated.userId());
                pushService.pushGroupUpdated(updated.groupId());
            }
        }
        // 通知申请人结果
        pushService.forwardTransient(updated.userId(),
                Envelope.of(Cmd.GROUP_JOIN_RESULT_PUSH, 0, updated));
        // 通知其它管理员该申请已处理（从待处理列表移除）
        groupService.membersOf(updated.groupId()).stream()
                .filter(member -> groupService.isManager(updated.groupId(), member))
                .forEach(manager -> pushService.forwardTransient(manager,
                        Envelope.of(Cmd.GROUP_JOIN_RESULT_PUSH, 0, updated)));
        return updated;
    }

    /** 我发出的 + 我有权限处理的待处理申请 */
    public List<GroupJoinRequest> listFor(String userId) {
        return requests.values().stream()
                .filter(r -> r.userId().equals(userId)
                        || (r.pending() && groupService.isManager(r.groupId(), userId)))
                .sorted(Comparator.comparingLong(GroupJoinRequest::ts).reversed())
                .limit(200)
                .toList();
    }

    public int pendingCount(String userId) {
        return (int) requests.values().stream()
                .filter(r -> r.userId().equals(userId)
                        || (r.pending() && groupService.isManager(r.groupId(), userId)))
                .filter(GroupJoinRequest::pending)
                .count();
    }

    public void removeGroup(String groupId) {
        requests.entrySet().removeIf(entry -> entry.getValue().groupId().equals(groupId));
    }

    public List<GroupJoinRequest> snapshot() {
        return List.copyOf(requests.values());
    }

    public void restore(List<GroupJoinRequest> list) {
        requests.clear();
        if (list != null) {
            list.forEach(r -> {
                if (r != null && r.id() != null) {
                    requests.put(r.id(), r);
                }
            });
        }
    }
}

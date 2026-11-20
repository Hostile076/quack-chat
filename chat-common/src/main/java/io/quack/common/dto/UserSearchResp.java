package io.quack.common.dto;

import io.quack.common.model.User;

import java.util.List;

/**
 * 搜索用户结果。
 *
 * <p>排序规则由服务端保证：
 * <ol>
 *   <li>如果关键字是纯数字且存在这个 QC 号，这条永远排第一（QC 号优先于同名用户名）；</li>
 *   <li>其余按 QC 号升序。</li>
 * </ol>
 */
public record UserSearchResp(List<User> users) {
}

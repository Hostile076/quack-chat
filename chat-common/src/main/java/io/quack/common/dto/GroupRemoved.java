package io.quack.common.dto;

/**
 * 群成员变更的“你已不在群里”通知。
 *
 * <p>被踢出、群被解散时推给当事人；客户端收到后要把会话从列表里移除，
 * 否则会留下一个发不出消息、也收不到消息的“僵尸会话”。
 *
 * @param groupId 群号
 * @param reason  原因（kicked / dismissed），只用于提示文案
 */
public record GroupRemoved(String groupId, String reason) {
}

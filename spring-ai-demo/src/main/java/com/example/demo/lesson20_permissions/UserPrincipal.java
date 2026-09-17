package com.example.demo.lesson20_permissions;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 第 20 课：多用户身份 —— 企业级 Agent 的第一块基石。
 *
 * <p>前面 19 课的所有请求都是"匿名的"：没有用户、没有角色，工具谁都能调、数据谁都能看。
 * 企业级的第一步是<b>身份</b>：每个请求带着"我是谁"进来，后续所有权限判断都基于它。</p>
 *
 * <p><b>Spring AI 的身份传递通道是 {@code ToolContext}</b>：
 * {@code ChatClient} 侧用 {@code .toolContext(Map)} 放入身份，工具方法声明一个
 * {@code ToolContext} 参数即可取到——身份走"旁路"，不进提示词（提示词不是安全边界，见本课反面教材）。
 * 与 LangChain 对照：≈ {@code RunnableConfig(configurable={"user_id", ...})} 的按请求配置传递。</p>
 *
 * <p>三个模拟用户贯穿本课与 lesson22：alice（管理员）、bob（客服）、carol（普通员工），
 * 权限递减，数据可见范围也随之递减。</p>
 */
public record UserPrincipal(String userId, String name, Role role, String dept) {

    /** 角色 = 权限层级：能看的数据密级 + 能执行的工具操作 */
    public enum Role {
        /** 普通员工：公开数据 */
        EMPLOYEE(0),
        /** 客服：可看内部数据，可执行取消订单等客服操作 */
        SUPPORT(1),
        /** 管理员：全部数据 + 全部操作 */
        ADMIN(2);

        private final int level;

        Role(int level) {
            this.level = level;
        }

        /** 数据密级可见上限：文档密级 ≤ 此值才可见 */
        public int clearance() {
            return level;
        }
    }

    /** 数据密级（metadata 里存名字，比较用序数）：公开 < 内部 < 机密 */
    public enum DataLevel {
        PUBLIC, INTERNAL, CONFIDENTIAL;

        public int level() {
            return ordinal();
        }

        public static DataLevel of(String name) {
            return valueOf(name);
        }
    }

    /** 本用户能否看到该密级的数据 */
    public boolean canSee(DataLevel level) {
        return level.level() <= role.clearance();
    }

    // ---------- 模拟用户目录（生产里这是 SSO/LDAP/IdP，这里内存模拟保证离线可测） ----------

    private static final Map<String, UserPrincipal> DIRECTORY = List.of(
            new UserPrincipal("alice", "张管理", Role.ADMIN, "管理部"),
            new UserPrincipal("bob", "李客服", Role.SUPPORT, "客服部"),
            new UserPrincipal("carol", "王员工", Role.EMPLOYEE, "研发部"))
            .stream()
            .collect(Collectors.toUnmodifiableMap(UserPrincipal::userId, Function.identity()));

    /** 按登录名解析用户；未知登录名返回 null（由调用方决定怎么拒绝） */
    public static UserPrincipal resolve(String userId) {
        return DIRECTORY.get(userId);
    }

    public static List<UserPrincipal> all() {
        return List.copyOf(DIRECTORY.values());
    }

    /** 从 ToolContext 取身份；没有身份视为"未登录"（返回 null，工具层统一拒绝） */
    @SuppressWarnings("unchecked")
    public static <T> T fromContext(Map<String, Object> context) {
        return context == null ? null : (T) context.get("user");
    }
}

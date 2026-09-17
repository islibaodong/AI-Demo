package com.example.demo.lesson24_skills;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.utils.Skills;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 24 课单元测试：不调用任何模型。验证技能的发现、一级披露边界
 * （元数据常驻、正文不常驻）、二级加载（按名展开正文）。
 */
class Lesson24SkillsTest {

    private static ToolCallback skillsTool() {
        return SkillsTool.builder()
                .addSkillsDirectory(Lesson24Controller.SKILLS_DIR)
                .build();
    }

    @Test
    void discoversBothSkillsFromDirectory() {
        List<org.springaicommunity.agent.tools.SkillsTool.Skill> skills =
                Skills.loadDirectory(Lesson24Controller.SKILLS_DIR);

        assertThat(skills).extracting(org.springaicommunity.agent.tools.SkillsTool.Skill::name)
                .containsExactlyInAnyOrder("expense-policy", "meeting-notes");
    }

    @Test
    void level1MetadataResidesInToolDescriptionButBodyDoesNot() {
        // 一级披露：工具描述里编入了全部技能的 name/description——模型平时只看到这些
        String description = skillsTool().getToolDefinition().description();
        assertThat(description).contains("expense-policy").contains("meeting-notes");
        assertThat(description).contains("报销政策专家").contains("会议纪要");

        // 关键边界：正文细节不常驻（对比：正文里有"600 元"和"5 个工作日"）
        assertThat(description).doesNotContain("600 元").doesNotContain("5 个工作日");
    }

    @Test
    void level2LoadingExpandsSkillBodyOnDemand() {
        // 二级加载：模型调用 skill 工具后，对应技能的正文才展开
        String output = skillsTool().call("{\"command\": \"expense-policy\"}");

        assertThat(output).contains("expense-policy");
        assertThat(output).contains("600 元").contains("5 个工作日");
        // 装载的是正文，别的技能的正文不该出现
        assertThat(output).doesNotContain("参会");
    }

    @Test
    void loadingUnknownSkillDoesNotThrow() {
        // 技能名错了也不该炸链路——返回错误话术由模型读后自行处理（与 lesson16 同思路）
        String output = skillsTool().call("{\"command\": \"no-such-skill\"}");
        assertThat(output).isNotBlank();
    }
}

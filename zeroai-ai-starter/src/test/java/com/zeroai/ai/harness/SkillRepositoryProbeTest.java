package com.zeroai.ai.harness;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探测 Classpath 技能仓库的加载行为。
 * <p>
 * 该测试存在的意义：Skill 是 Harness 的「软权限」入口，一旦加载路径写错，
 * 模型会静默失去策略约束而不报错。必须用测试钉死「两个技能都在、内容非空」。
 */
class SkillRepositoryProbeTest {

    @Test
    @DisplayName("从 classpath 能加载两个商城技能")
    void loadsTwoSkills() throws Exception {
        try (AgentSkillRepository repo = new ClasspathSkillRepository("skills")) {
            List<String> names = repo.getAllSkillNames();
            System.out.println("[PROBE] skills = " + names);
            assertThat(names).contains("mall-shopping-guide", "order-query-guide");

            for (AgentSkill skill : repo.getAllSkills()) {
                System.out.println("[PROBE] skill=" + skill.getName()
                        + " desc=" + skill.getDescription()
                        + " contentLen=" + skill.getSkillContent().length());
                assertThat(skill.getDescription()).isNotBlank();
                assertThat(skill.getSkillContent()).isNotBlank();
            }
        }
    }
}

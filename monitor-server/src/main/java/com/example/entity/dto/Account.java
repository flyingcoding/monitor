package com.example.entity.dto;

import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.entity.BaseData;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 数据库中的用户信息。
 *
 * <p>v1.2 新增字段：
 * <ul>
 *   <li>{@code enabled} — 账号启用标志，禁用后 API Token 校验会拒绝；密码字段可为 null（OIDC 自动创建场景）。</li>
 * </ul>
 */
@Data
@TableName("account")
@NoArgsConstructor
@AllArgsConstructor
public class Account implements BaseData {
    @TableId(type = IdType.AUTO)
    Integer id;
    String username;
    String password;
    String email;
    String role;
    String clients;
    Date registerTime;
    /**
     * 账号启用标志：0=禁用，1=启用。
     * <p>v1.2 引入；MyBatis-Plus 在 update 场景下默认 {@code FieldStrategy.NOT_NULL} 会忽略 null，
     * 现有 update 调用均未涉及此字段，无需显式 ALWAYS。
     */
    Boolean enabled;

    public List<Integer> getClientList(){
        if (this.clients == null) return Collections.emptyList();
        return JSONArray.parse(this.clients).toList(Integer.class);
    }
}

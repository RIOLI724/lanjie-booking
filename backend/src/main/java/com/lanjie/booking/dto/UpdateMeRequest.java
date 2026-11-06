package com.lanjie.booking.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateMeRequest {

    @Size(max = 20, message = "昵称最长 20 字")
    private String nickname;

    @Size(max = 255, message = "头像地址过长")
    private String avatarUrl;
}

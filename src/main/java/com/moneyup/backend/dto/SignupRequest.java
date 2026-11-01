package com.moneyup.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

public class SignupRequest {
    @Getter
    @Setter
    @NotBlank(message = "Username cannot be blank")
    @Size(min = 3, max = 20, message = "Username must be between 3 and 20")
    private String username;
    @Getter
    @Setter
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;
    @Getter
    @Setter
    @NotBlank(message = "Must provide a password")
    @Size(min=6, message = "Password must have at least 6 characters")
    private String password;


}

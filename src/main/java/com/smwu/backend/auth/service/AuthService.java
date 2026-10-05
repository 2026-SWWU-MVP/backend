package com.smwu.backend.auth.service;

import com.smwu.backend.auth.dto.LoginRequest;
import com.smwu.backend.auth.dto.SignupRequest;
import com.smwu.backend.auth.dto.UserResponse;
import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.user.domain.User;
import com.smwu.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 아이디/비밀번호 가입·로그인 (MVP: 세션·JWT 없음, 설계서 3.6) */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public boolean isLoginIdAvailable(String loginId) {
        return !userRepository.existsByLoginId(loginId);
    }

    @Transactional
    public UserResponse signup(SignupRequest request) {
        if (userRepository.existsByLoginId(request.loginId())) {
            throw new BusinessException(ErrorCode.LOGIN_ID_DUPLICATED);
        }
        try {
            User user = userRepository.saveAndFlush(
                    new User(request.loginId(), passwordEncoder.encode(request.password()), request.name().strip()));
            return UserResponse.of(user);
        } catch (DataIntegrityViolationException e) {
            // 같은 아이디로 동시에 가입한 경우
            throw new BusinessException(ErrorCode.LOGIN_ID_DUPLICATED);
        }
    }

    /** 아이디가 없든 비밀번호가 틀리든 같은 401 (어느 쪽이 틀렸는지 알려주지 않음) */
    @Transactional(readOnly = true)
    public UserResponse login(LoginRequest request) {
        return userRepository.findByLoginId(request.loginId())
                .filter(user -> passwordEncoder.matches(request.password(), user.getPasswordHash()))
                .map(UserResponse::of)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));
    }
}

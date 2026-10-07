package com.smwu.backend.user.service;

import com.smwu.backend.user.domain.User;
import com.smwu.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 작성자·확정자 ID → 이름 (화면에 "누가 했는지" 표시, 설계서 3.4).
 * 내보낸 강사도 계정은 남으므로 이름이 그대로 나온다. 로그인 기능 이전 데이터는 null.
 */
@Component
@RequiredArgsConstructor
public class UserNames {

    private final UserRepository userRepository;

    public String name(Long userId) {
        return userId == null ? null : userRepository.findById(userId).map(User::getName).orElse(null);
    }

    public Map<Long, String> names(Collection<Long> userIds) {
        return userRepository.findAllById(userIds.stream().filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
    }
}

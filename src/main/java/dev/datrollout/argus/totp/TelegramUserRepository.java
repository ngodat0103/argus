package dev.datrollout.argus.totp;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelegramUserRepository extends JpaRepository<TelegramUserEntity, Long> {

    Optional<TelegramUserEntity> findByChatId(Long chatId);
}

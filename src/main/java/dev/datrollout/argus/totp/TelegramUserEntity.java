package dev.datrollout.argus.totp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "telegram_user")
@Getter
@NoArgsConstructor
public class TelegramUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", unique = true, nullable = false)
    private Long chatId;

    @Setter
    @Column(name = "totp_secret")
    private String totpSecret;

    public TelegramUserEntity(Long chatId) {
        this.chatId = chatId;
    }
}

package com.finance.dart.mail.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String sender;

    /**
     * 메일 전송(HTML)
     * @param to
     * @param subject
     * @param html
     * @throws MessagingException
     * @return
     */
    public boolean sendHtmlMail(String to, String subject, String html) {

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(sender);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true); // true = HTML 지원

            mailSender.send(mimeMessage);

        } catch (Exception e) {
            return false;
        }

        return true;
    }

}

package com.modernchat.service;

import com.modernchat.common.ChatMode;
import com.modernchat.common.ClanType;
import com.modernchat.event.ChatMessageSentEvent;
import com.modernchat.event.ChatPrivateMessageSentEvent;
import com.modernchat.event.ChatSendLockedEvent;
import com.modernchat.event.SubmitHistoryEvent;
import com.modernchat.util.StringUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Singleton
public class MessageService implements ChatService
{
    private static final int SEND_COOLDOWN_MS = 900;
    private static final int SEND_HOT_MESSAGE_MAX = 5;
    private static final int SEND_LOCK_MS = 1250;
    private static final int SEND_LOCK_COUNT_RESET_MS = 60000;

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private EventBus eventBus;
    @Inject private FilterService filterService;

    @Getter private volatile long lastSendTimestamp = 0L;
    @Getter private volatile long lastSendLock = 0L;
    @Getter private volatile long sendLockedUntil = 0L;

    private final AtomicInteger sendHotMessageCount = new AtomicInteger(0);
    private final AtomicInteger sendLockCount = new AtomicInteger(0);

    @Override
    public void startUp() {

    }

    @Override
    public void shutDown() {
        lastSendTimestamp = 0L;
    }

    protected void resetLocks() {
        lastSendLock = 0L;
        sendLockedUntil = 0L;
        sendLockCount.set(0);
        sendHotMessageCount.set(0);
    }

    public void sendMessage(String text, ChatMode mode, @Nullable String targetName) {
        if (StringUtil.isNullOrEmpty(text))
            return;

        if (filterService.isFiltered(text))
            return;

        if (processSendLock()) {
            log.debug("Message send is locked, cannot send message: {}", text);
            notifyLocked(targetName, false);
            return;
        }

        ClanType clanType = ClanType.NORMAL; // default clan type
        ChatMode selectedMode = mode;

        switch (mode) {
            case PUBLIC:
                break;
            case FRIENDS_CHAT:
                break;
            case CLAN_MAIN:
                break;
            case CLAN_GUEST:
                break;

            // Custom
            case CLAN_GIM:
                clanType = ClanType.IRONMAN;
                selectedMode = ChatMode.CLAN_MAIN;
                break;
            case PRIVATE:
                if (StringUtil.isNullOrEmpty(targetName)) {
                    log.warn("Attempted to send private chat message to no target");
                    return;
                }
                sendPrivateChat(text, targetName);
                return; // skip the rest of the logic
        }

        lastSendTimestamp = System.currentTimeMillis();

        final int modeValue = selectedMode.getValue();
        final int clanTypeValue = clanType.getValue();

        clientThread.invoke(() -> {
            client.runScript(ScriptID.CHAT_SEND, text, modeValue, clanTypeValue, 0, 0);

            eventBus.post(new ChatMessageSentEvent(text, modeValue, clanTypeValue));
        });
    }

    public void sendPrivateChat(String text, String targetName) {
        if (StringUtil.isNullOrEmpty(text)) {
            log.warn("Attempted to send empty private chat message");
            return;
        }

        if (StringUtil.isNullOrEmpty(targetName)) {
            log.warn("Attempted to send private chat without a target name");
            return;
        }

        if (filterService.isFiltered(text))
            return;

        if (processSendLock()) {
            log.debug("Private message send is locked, cannot send message: {}", text);
            notifyLocked(targetName, true);
            return;
        }

        lastSendTimestamp = System.currentTimeMillis();

        clientThread.invoke(() -> {
            client.runScript(ScriptID.PRIVMSG, targetName, text);

            eventBus.post(new SubmitHistoryEvent(text));
            eventBus.post(new ChatPrivateMessageSentEvent(text, targetName));
        });
    }

    private void notifyLocked(String targetName, boolean isPrivate) {
        eventBus.post(new ChatSendLockedEvent(targetName, getSendLockedUntil(), isPrivate));
    }

    private boolean processSendLock() {
        if (isSendLocked())
            return true;

        if (!isSendCooldownActive())
            return false;

        sendHotMessageCount.incrementAndGet();
        if (sendHotMessageCount.get() < SEND_HOT_MESSAGE_MAX)
            return false;

        long now = System.currentTimeMillis();
        if (now - lastSendLock >= SEND_LOCK_COUNT_RESET_MS)
            sendLockCount.set(0);

        lastSendLock = now;
        long lockCount = sendLockCount.incrementAndGet();
        long lockDelay = Math.min(lockCount * SEND_LOCK_MS, SEND_LOCK_COUNT_RESET_MS);
        sendLockedUntil = lastSendLock + lockDelay;
        return true;
    }

    public boolean isSendCooldownActive() {
        return System.currentTimeMillis() - lastSendTimestamp < SEND_COOLDOWN_MS;
    }

    public boolean isSendLocked() {
        return System.currentTimeMillis() < sendLockedUntil;
    }
}

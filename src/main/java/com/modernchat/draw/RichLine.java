package com.modernchat.draw;

import lombok.Data;
import net.runelite.api.ChatMessageType;

import java.util.ArrayList;
import java.util.List;

@Data
public final class RichLine
{
    private final List<TextSegment> segs = new ArrayList<>();
    private ChatMessageType type = ChatMessageType.GAMEMESSAGE;
    private long timestamp;
    private String sender;
    private String receiver;
    private String targetName;

    // Cached values for performance
    private List<VisualLine> lineCache = null;

    public void resetCache() {
        if (lineCache != null) {
            lineCache.clear();
        }
        lineCache = null;

        for (TextSegment seg : segs) {
            seg.resetCache();
        }
    }
}
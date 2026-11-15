package com.malbers.eversignal;

import java.util.HashMap;
import java.util.Map;

/**
 * Pretty names for flag chips (flags only; value rows keep the device's raw names).
 * None of these fields are documented: names are read from the field names. Unknown keys keep their raw name.
 */
public final class Names {
    private Names() {}

    private static final Map<String, String> M = new HashMap<>();
    static {
        // player flags (getState)
        M.put("hasPlayQueue", "Play queue available");
        M.put("hasPlayMode", "Repeat/shuffle available");
        M.put("hasFavor", "Can favourite");
        M.put("hasLyric", "Lyrics available");
        M.put("hasSearchLyric", "Can search lyrics");
        M.put("hasEQSetting", "EQ available");
        M.put("eqActive", "EQ on");
        M.put("hasDspSetting", "DSP available");
        M.put("dspActive", "DSP on");
        M.put("hasSubSetting", "Subwoofer available");
        M.put("subActive", "Subwoofer on");
        M.put("isSeeAlbumAndArtist", "Album & artist pages");
        M.put("neteaseLoginState", "NetEase signed in");
        // now-playing screen (everSoloPlayInfo)
        M.put("isCanSeek", "Can seek");
        M.put("isCanNextPlay", "Can skip forward");
        M.put("isCanLastPlay", "Can skip back");
        M.put("isCanChangePlayStatus", "Can play/pause");
        M.put("canChangePlayMode", "Can change repeat/shuffle");
        M.put("canFavor", "Can favourite");
        M.put("hasQueue", "Has queue");
        M.put("isMultRoom", "Multiroom");
        M.put("isLMS", "Lyrion/Squeezebox (LMS)");
        M.put("isQPlay", "QPlay");
        M.put("isQQ", "QQ Music");
        M.put("isOutInfoEnabel", "Output info available");
        M.put("isOnlineInfo", "Online info");
        M.put("isOpenApp", "Opened from an app");
        M.put("isHasDSP", "Has DSP");
        M.put("isHasSub", "Has subwoofer");
        M.put("isHasSpectrum", "Has spectrum");
        M.put("isVuMode", "VU screen showing");
        M.put("iShowVu", "Stock screen shows VU");
        M.put("iShowAlbumIcon", "Stock screen shows cover");
        M.put("iShowCurrentPosition", "Stock screen shows position");
        M.put("iShowDuration", "Stock screen shows duration");
        M.put("isCanShowSeekBar", "Stock screen shows seek bar");
        M.put("isCanShowMode", "Stock screen shows mode");
        M.put("isCanShowPlayQueue", "Stock screen shows queue");
        // track flags (playingMusic + library item)
        M.put("hasNextPlay", "Has next track");
        M.put("hasLastPlay", "Has previous track");
        M.put("isMix", "Mix / radio");
        M.put("favor", "Favourite");
        M.put("isLossless", "Lossless (library flag)");
        M.put("isMQA", "MQA file");
        M.put("isLibrary", "Library track");
        M.put("analyzed", "Analysed");
        M.put("active", "In queue");
        M.put("exist", "File exists");
        M.put("isLocalCueSong", "CUE track");
        M.put("isWebDav", "From WebDAV");
        // capabilities (getModel)
        M.put("ableRemoteBoot", "Remote power-on");
        M.put("ableRemoteShutdown", "Remote shutdown");
        M.put("ableRemoteReboot", "Remote reboot");
        M.put("ableRemoteSleep", "Remote sleep");
        M.put("ableMusicService", "Music services");
        M.put("ableMusicTest", "Music test mode (?)");
        M.put("hasDrcSetting", "DRC settings");
        M.put("hasEqSetting", "EQ settings");
        M.put("hasMultroom", "Multiroom");
        M.put("hasSouceInSetting", "Source input settings (?)");
        M.put("isOnlyOutput", "Output-only device (?)");
        M.put("isNewphone", "New phone app (?)");
        // volume flags (volumeData)
        M.put("isLock", "Volume locked");
        M.put("isMute", "Muted");
        M.put("isVolumeEnable", "Volume control on");
        M.put("isNumberVolume", "Numeric volume display");
        M.put("isDsdOut", "DSD output (meaning unclear)");
    }

    /** Pretty name for a raw flag key, or null if unknown. */
    public static String of(String rawKey) { return M.get(rawKey); }
}

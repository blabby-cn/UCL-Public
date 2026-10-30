package net.kdt.pojavlaunch.value;


import android.graphics.BitmapFactory;
import android.util.Log;

import net.kdt.pojavlaunch.*;
import net.kdt.pojavlaunch.utils.FileUtils;

import java.io.*;
import com.google.gson.*;
import android.graphics.Bitmap;
import android.util.Base64;

import androidx.annotation.Keep;

import org.apache.commons.io.IOUtils;

@SuppressWarnings("IOStreamConstructor")
@Keep
public class MinecraftAccount {
    /* Storage key: the base name of the json file holding this account, and the
     * only thing that identifies an account on disk.
     *
     * It used to be the username, which made the username the primary key: a
     * second account called Mike -- a different Microsoft identity, an external
     * authlib login, anything -- overwrote the first one. Files are now named
     * after this key instead, so <em>any</em> number of accounts may share a
     * username. Loaded accounts always get this filled in by load(); accounts
     * built in memory get a free key assigned on the first save(). */
    public String accountFile;
    public String accessToken = "0"; // access token
    public String clientToken = "0"; // clientID: refresh and invalidate
    public String profileId = "00000000-0000-0000-0000-000000000000"; // profile UUID, for obtaining skin
    public String username = "Steve";
    /* Blabby account system username; empty for every other kind of account.
     * Blabby has no Minecraft auth server, so those accounts are stored like
     * offline ones but must still be presented as Blabby accounts. */
    public String blabbyUser;
    /* Base URL of the authlib server this account came from. Empty for offline,
     * Blabby and Microsoft accounts; external auth servers use it to resolve
     * the skin (and therefore the avatar) on their own session endpoint. */
    public String authBaseUrl;
    public String selectedVersion = "1.7.10";
    public boolean isMicrosoft = false;
    public String msaRefreshToken = "0";
    public String xuid;
    public long expiresAt;
    public String skinFaceBase64;
    private Bitmap mFaceCache;
    
    void updateSkinFace(String uuid) {
        /* The avatar is the head of the account's own skin, cropped locally
         * without any resampling filter, so 128px skins keep their detail and
         * the hat layer is composited on top. */
        net.kdt.pojavlaunch.mcui.AvatarLoader.fetchAndStore(this);
    }

    public boolean isLocal(){
        return accessToken.equals("0") && !username.startsWith("Demo.");
    }

    public boolean isDemo(){
        return username.startsWith("Demo.");
    }
    
    public void updateSkinFace() {
        updateSkinFace(profileId);
    }
    
    public String save(String outPath) throws IOException {
        Tools.write(outPath, Tools.GLOBAL_GSON.toJson(this));
        return username;
    }
    
    /** Writes the account and returns its storage key (not the username). */
    public String save() throws IOException {
        String key = resolveAccountFile();
        Tools.write(Tools.DIR_ACCOUNT_NEW + "/" + key + ".json", Tools.GLOBAL_GSON.toJson(this));
        return key;
    }

    /** The key this account is stored under, or null when never saved. */
    @androidx.annotation.Nullable
    public String storageKey() {
        return accountFile;
    }

    /**
     * Fixes this account's storage key.
     *
     * <p>An account that came from disk keeps the key it was loaded with, so
     * saving it again overwrites its own file and nothing else. A brand new
     * account takes the username if that name is free, and otherwise the first
     * free "<username>-N" -- two accounts may be called Mike, both stay.
     */
    private String resolveAccountFile() {
        if (accountFile != null && !accountFile.isEmpty()) return accountFile;
        /* signing in to an identity that is already stored updates that file
         * instead of piling up a duplicate on every login */
        String sameIdentity = findSameIdentityKey();
        if (sameIdentity != null) {
            accountFile = sameIdentity;
            return sameIdentity;
        }
        String base = sanitizeKey(username == null || username.isEmpty() ? "account" : username);
        String candidate = base;
        int suffix = 2;
        while (new File(Tools.DIR_ACCOUNT_NEW, candidate + ".json").exists()) {
            candidate = base + "-" + suffix;
            suffix++;
            if (suffix > 100000) {
                /* pathological, but never loop forever or reuse a file */
                candidate = base + "-" + System.currentTimeMillis();
                break;
            }
        }
        accountFile = candidate;
        return candidate;
    }

    /**
     * Key of the stored account that is this very identity, or null.
     *
     * <p>Matches on the Minecraft uuid, falling back to the Xbox user id. Two
     * accounts that merely share a username never match here: those are
     * different logins and get separate files.
     */
    @androidx.annotation.Nullable
    private String findSameIdentityKey() {
        String id = profileId == null ? ""
                : profileId.replace("-", "").toLowerCase(java.util.Locale.US);
        boolean hasProfile = !id.isEmpty() && !id.equals(NIL_UUID);
        String xuid = this.xuid;
        boolean hasXuid = xuid != null && !xuid.isEmpty();
        if (!hasProfile && !hasXuid) return null;

        String[] names = new File(Tools.DIR_ACCOUNT_NEW).list();
        if (names == null) return null;
        for (String file : names) {
            if (!file.endsWith(".json")) continue;
            String key = file.substring(0, file.length() - 5);
            MinecraftAccount other = load(key);
            if (other == null) continue;
            if (hasProfile) {
                String otherId = other.profileId == null ? ""
                        : other.profileId.replace("-", "").toLowerCase(java.util.Locale.US);
                if (id.equals(otherId)) return key;
            } else if (xuid.equals(other.xuid)) {
                return key;
            }
        }
        return null;
    }

    private static final String NIL_UUID = "00000000000000000000000000000000";

    /** Keeps a username usable as a file name on every file system. */
    private static String sanitizeKey(String name) {
        String key = name.replaceAll("[\\\\\\\\/:*?\"<>|\\s]", "_").trim();
        if (key.isEmpty()) key = "account";
        if (key.length() > 48) key = key.substring(0, 48);
        /* "." and ".." are directories, not names */
        if (key.equals(".") || key.equals("..")) key = "account";
        return key;
    }
    
    public static MinecraftAccount parse(String content) throws JsonSyntaxException {
        return Tools.GLOBAL_GSON.fromJson(content, MinecraftAccount.class);
    }

    /** @param name the storage key (file base name), not the username */
    public static MinecraftAccount load(String name) {
        if(!accountExists(name)) return null;
        try {
            MinecraftAccount acc = parse(Tools.read(Tools.DIR_ACCOUNT_NEW + "/" + name + ".json"));
            /* the file name is the identity: it wins over whatever the json says */
            acc.accountFile = name;
            if (acc.accessToken == null) {
                acc.accessToken = "0";
            }
            if (acc.clientToken == null) {
                acc.clientToken = "0";
            }
            if (acc.profileId == null) {
                acc.profileId = "00000000-0000-0000-0000-000000000000";
            }
            if (acc.username == null) {
                acc.username = "0";
            }
            if (acc.selectedVersion == null) {
                acc.selectedVersion = "1.7.10";
            }
            if (acc.msaRefreshToken == null) {
                acc.msaRefreshToken = "0";
            }
            return acc;
        } catch(IOException | JsonSyntaxException e) {
            Log.e(MinecraftAccount.class.getName(), "Caught an exception while loading the profile",e);
            return null;
        }
    }

    public Bitmap getSkinFace(){
        /* account scoped cache first: two accounts both called Mike must not
         * end up showing the same head */
        Bitmap scoped = net.kdt.pojavlaunch.mcui.AvatarLoader.cached(this);
        if (scoped != null) {
            mFaceCache = scoped;
            return scoped;
        }
        if(isLocal()) return null;

        File skinFaceFile = getSkinFaceFile(username);
        if (!skinFaceFile.exists()) {
            // Legacy version, storing the head inside the json as base 64
            if(skinFaceBase64 == null) return null;
            byte[] faceIconBytes = Base64.decode(skinFaceBase64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(faceIconBytes, 0, faceIconBytes.length);
        } else {
            if(mFaceCache == null) {
                mFaceCache = BitmapFactory.decodeFile(skinFaceFile.getAbsolutePath());
            }
        }

        return mFaceCache;
    }

    public static Bitmap getSkinFace(String username) {
        return BitmapFactory.decodeFile(getSkinFaceFile(username).getAbsolutePath());
    }

    private static File getSkinFaceFile(String username) {
        return new File(Tools.DIR_CACHE, username + ".png");
    }

    private static boolean accountExists(String username){
        return new File(Tools.DIR_ACCOUNT_NEW + "/" + username + ".json").exists();
    }

    /** Deletes this account's own file. Never touches a same-named sibling. */
    public boolean deleteStoredFile() {
        if (accountFile == null || accountFile.isEmpty()) return false;
        return new File(Tools.DIR_ACCOUNT_NEW, accountFile + ".json").delete();
    }

    /**
     * The stored account carrying this Microsoft refresh token, or null.
     *
     * <p>Token refresh used to look the account up by username and then
     * overwrite that file, which hit the wrong account the moment two of them
     * shared a name. The refresh token identifies exactly one account.
     */
    @androidx.annotation.Nullable
    public static MinecraftAccount findByRefreshToken(String token) {
        if (token == null || token.isEmpty() || "0".equals(token)) return null;
        String[] names = new File(Tools.DIR_ACCOUNT_NEW).list();
        if (names == null) return null;
        for (String file : names) {
            if (!file.endsWith(".json")) continue;
            MinecraftAccount account = load(file.substring(0, file.length() - 5));
            if (account != null && token.equals(account.msaRefreshToken)) return account;
        }
        return null;
    }
}

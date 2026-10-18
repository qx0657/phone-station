package dev.phonestation.adbkeep;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.phonestation.adbkeep.link.LinkAuth;
import dev.phonestation.adbkeep.link.LinkInvite;
import dev.phonestation.adbkeep.link.LinkQr;
import dev.phonestation.adbkeep.link.LocalLink;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;

/** 加入、邀请识别、授权列表和撤销。不申请相机权限，也不改无线调试配对。 */
public final class PairingActivity extends StationActivity {
    private static final int PHOTO = 41;
    private StationChrome ui;
    private TextView feedback;
    private EditText manual;
    private LinearLayout pendingRows;
    private LinearLayout grantRows;
    private String confirming;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new StationChrome(this);
        ui.back("工位配对");
        LinearLayout intro = ui.card();
        ui.paragraph(intro, "本地链路直接交换公钥。没有 adb 时用系统相机拍照，回到这里解码；也可以手动输入 12 位邀请码。应用不申请相机权限。");
        ui.paragraph(intro, "无线调试自己的配对二维码仍在连接设置里。邀请要等中继 6 才向公网登记，这一页不拨公网。");
        ui.action(intro, "用系统相机拍照", true, view -> capture());
        manual = ui.field(intro, "12 位邀请码", "例如 111111222222", false);
        ui.action(intro, "识别邀请码", false, view -> readManual());
        feedback = ui.paragraph(intro, "");
        feedback.setVisibility(View.GONE);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        LinearLayout pending = ui.card();
        ui.groupTitle(pending, "等待确认");
        pendingRows = new LinearLayout(this);
        pendingRows.setOrientation(LinearLayout.VERTICAL);
        pending.addView(pendingRows);

        LinearLayout granted = ui.card();
        ui.groupTitle(granted, "已授权的 Mac");
        grantRows = new LinearLayout(this);
        grantRows.setOrientation(LinearLayout.VERTICAL);
        granted.addView(grantRows);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void capture() {
        Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (capture.resolveActivity(getPackageManager()) == null) {
            show("这台手机没有可用的系统相机。可以改为手动输入。", false);
            return;
        }
        try {
            File photo = PairPhotoProvider.file(this);
            if (photo.exists() && !photo.delete()) {
                show("上一张照片还在，请重试。", false);
                return;
            }
            Uri uri = PairPhotoProvider.uri();
            capture.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            capture.setClipData(ClipData.newRawUri("", uri));
            capture.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(capture, PHOTO);
        } catch (RuntimeException error) {
            show("没能打开系统相机。可以改为手动输入。", false);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PHOTO) {
            return;
        }
        File photo = PairPhotoProvider.file(this);
        try {
            if (result != RESULT_OK) {
                show("没有拍照。", false);
                return;
            }
            Bitmap bitmap = BitmapFactory.decodeFile(photo.getAbsolutePath());
            if (bitmap == null && data != null) {
                Object extra = data.getExtras() == null ? null : data.getExtras().get("data");
                if (extra instanceof Bitmap) {
                    bitmap = (Bitmap) extra;
                }
            }
            if (bitmap == null) {
                show("这张照片解不出来。", false);
                return;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            acceptText(LinkQr.decode(pixels, width, height));
        } catch (RuntimeException error) {
            show("这张照片里没有工位邀请。", false);
        } finally {
            if (photo.exists()) {
                photo.delete();
            }
        }
    }

    private void readManual() {
        String code = manual.getText().toString().trim();
        try {
            LinkInvite.manualParts(code);
            show("邀请码格式正确。加入前要在手机上比对 6 位安全码。中继 6 还没部署，这一页不拨公网。", true);
        } catch (IllegalArgumentException error) {
            show("邀请码是 12 位小写十六进制。", false);
        }
    }

    private void acceptText(String text) {
        try {
            LinkInvite invite = LinkInvite.parse(text);
            java.nio.file.Path stored = getFilesDir().toPath().resolve("link-invite");
            Files.write(stored, invite.text().getBytes(StandardCharsets.US_ASCII), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.setPosixFilePermissions(stored, PosixFilePermissions.fromString("rw-------"));
            show("phone".equals(invite.kind)
                    ? "已识别手机邀请，并保存在这台手机上。中继 6 还没部署，这一页不拨公网。"
                    : "已识别电脑邀请，并保存在这台手机上。中继 6 还没部署，这一页不拨公网。", true);
        } catch (IllegalArgumentException error) {
            show("这张照片里没有工位邀请。", false);
        } catch (java.io.IOException error) {
            show("邀请没有保存，请重试。", false);
        }
    }

    private void render() {
        pendingRows.removeAllViews();
        grantRows.removeAllViews();
        LinkPairDesk desk = LinkPairing.desk;
        if (desk == null) {
            ui.paragraph(pendingRows, "先打开 MCP 服务，再在这里确认这台 Mac。");
            ui.paragraph(grantRows, "还没有授权。");
            return;
        }
        if (desk.pending().isEmpty()) {
            ui.paragraph(pendingRows, "没有等待确认的电脑。");
        }
        for (LinkPairDesk.Pending item : desk.pending()) {
            String id = hexPrefix(LinkAuth.deviceId(item.spki));
            ui.paragraph(pendingRows, item.name + " · " + id + " · 安全码 " + item.code);
            ui.action(pendingRows, "允许", true, view -> decide(item.spki, true));
            ui.action(pendingRows, "拒绝", false, view -> decide(item.spki, false));
        }
        if (desk.granted().isEmpty()) {
            ui.paragraph(grantRows, "还没有授权。");
        }
        for (LocalLink.Device device : desk.granted()) {
            String id = hexPrefix(device.id);
            String token = Base64.getEncoder().encodeToString(device.spki);
            ui.paragraph(grantRows, id);
            if (token.equals(confirming)) {
                ui.paragraph(grantRows, "撤销后这台 Mac 要重新邀请才能再连。已经执行的操作无法撤回。");
                ui.action(grantRows, "确认撤销", true, view -> revoke(device.spki));
                ui.action(grantRows, "取消", false, view -> {
                    confirming = null;
                    render();
                });
            } else {
                ui.action(grantRows, "撤销", false, view -> {
                    confirming = token;
                    render();
                });
            }
        }
    }

    private void decide(byte[] spki, boolean allow) {
        LinkPairDesk desk = LinkPairing.desk;
        if (desk == null) {
            show("先打开 MCP 服务。", false);
            return;
        }
        try {
            if (allow) {
                desk.allow(spki);
                show("这台 Mac 已允许。", true);
            } else {
                desk.deny(spki);
                show("已拒绝。", true);
            }
        } catch (Exception error) {
            show("没有写入授权，请重试。", false);
        }
        render();
    }

    private void revoke(byte[] spki) {
        LinkPairDesk desk = LinkPairing.desk;
        confirming = null;
        if (desk == null) {
            show("先打开 MCP 服务。", false);
            return;
        }
        try {
            desk.revoke(spki);
            show("已撤销。已经执行的操作无法撤回。", true);
        } catch (Exception error) {
            show("没有写入授权，请重试。", false);
        }
        render();
    }

    private void show(String message, boolean ok) {
        feedback.setText(StationText.translate(message));
        feedback.setTextColor(ok ? ui.held() : getColor(R.color.error));
        feedback.setVisibility(View.VISIBLE);
    }

    private static String hexPrefix(byte[] id) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 4 && i < id.length; i++) {
            out.append(String.format("%02x", id[i] & 0xff));
        }
        return out.toString();
    }
}

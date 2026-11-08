package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.LayoutHelper;

/**
 * One person in a Secured list: Telegram's {@link AvatarDrawable} (letters from the name) plus two lines, optionally a
 * Telegram {@link CheckBox2}. Used for the member list and the contact picker. It takes plain strings only, so no
 * Telegram user object exists anywhere (ManageChatUserCell and UserCell need one).
 */
final class SecuredMemberRow extends FrameLayout {

    private final AvatarDrawable avatar = new AvatarDrawable(SecuredResourcesProvider.get());
    private final TextView nameView;
    private final TextView subView;
    private final CheckBox2 check;
    private final Paint divider = new Paint();
    private boolean drawDivider;

    SecuredMemberRow(Context context, boolean withCheck) {
        super(context);
        setWillNotDraw(false);
        setBackgroundColor(Kit.color(Theme.key_windowBackgroundWhite));
        divider.setColor(Kit.color(Theme.key_divider));

        final View avatarView = new View(context) {
            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                avatar.setBounds(0, 0, getWidth(), getHeight());
                avatar.draw(canvas);
            }
        };
        addView(avatarView, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        nameView = Kit.text(context, "", 16, Kit.color(Theme.key_windowBackgroundWhiteBlackText), false);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        subView = Kit.text(context, "", 13, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
        subView.setSingleLine(true);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        texts.addView(subView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 72, 0, withCheck ? 56 : 16, 0));

        if (withCheck) {
            check = new CheckBox2(context, 21, SecuredResourcesProvider.get());
            check.setColor(-1, Theme.key_windowBackgroundWhite, Theme.key_checkboxCheck);
            check.setDrawUnchecked(true);
            check.setDrawBackgroundAsArc(10);
            addView(check, LayoutHelper.createFrame(24, 24, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
        } else {
            check = null;
        }
    }

    void set(long id, String name, CharSequence sub, boolean checked, boolean showDivider) {
        // AvatarDrawable takes initials from first and last name: split at the last space, as the chat header does.
        final String shown = name == null ? "" : name.trim();
        final int sp = shown.lastIndexOf(' ');
        avatar.setInfo(id, sp > 0 ? shown.substring(0, sp).trim() : shown, sp > 0 ? shown.substring(sp + 1) : null);
        nameView.setText(shown);
        subView.setText(sub);
        subView.setVisibility(TextUtils.isEmpty(sub) ? View.GONE : View.VISIBLE);
        if (check != null) check.setChecked(checked, false);
        drawDivider = showDivider;
        invalidate();
    }

    void setChecked(boolean checked) {
        if (check != null) check.setChecked(checked, true);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(dp(56) + (drawDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (drawDivider) canvas.drawLine(dp(72), getHeight() - 1, getWidth(), getHeight() - 1, divider);
    }
}

package com.zhihuyitu.care.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zhihuyitu.care.BuildConfig;
import com.zhihuyitu.care.R;

public class ProfileFragment extends Fragment {
    static final String DEVICE_REQUEST = "profile_device_request";

    public ProfileFragment() {
        super(R.layout.fragment_profile);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        // TODO FR-001/FR-017: Populate the avatar, nickname and account state from the shared Flask account profile.
        LinearLayout actions = view.findViewById(R.id.profile_actions);
        addAction(actions, R.string.profile_personal, R.drawable.ic_profile_person, null, this::showPersonal);
        addAction(actions, R.string.profile_devices, R.drawable.ic_profile_device,
                getString(R.string.profile_device_state), this::showDevices);
        addAction(actions, R.string.profile_edit, R.drawable.ic_profile_edit,
                getString(R.string.profile_edit_summary), this::showEditChoices);
        addAction(actions, R.string.profile_support, R.drawable.ic_profile_support,
                getString(R.string.profile_support_name), this::showSupport);
        addAction(actions, R.string.profile_check_updates, R.drawable.ic_profile_update,
                getString(R.string.profile_version_badge, BuildConfig.VERSION_NAME), this::showVersion);
        addAction(actions, R.string.profile_logout, R.drawable.ic_profile_logout, null, this::showLogout);
        getParentFragmentManager().setFragmentResultListener(DEVICE_REQUEST, getViewLifecycleOwner(),
                (key, result) -> showDevices());
    }

    private void addAction(LinearLayout actions, @StringRes int title, @DrawableRes int icon,
                           @Nullable String detail, Runnable action) {
        if (actions.getChildCount() > 0) {
            actions.getChildAt(actions.getChildCount() - 1)
                    .findViewById(R.id.profile_action_divider).setVisibility(View.VISIBLE);
        }
        View row = getLayoutInflater().inflate(R.layout.item_profile_action, actions, false);
        ((TextView) row.findViewById(R.id.profile_action_title)).setText(title);
        ((ImageView) row.findViewById(R.id.profile_action_icon)).setImageResource(icon);
        TextView summary = row.findViewById(R.id.profile_action_summary);
        if (detail != null) {
            summary.setText(detail);
            summary.setVisibility(View.VISIBLE);
        }
        row.findViewById(R.id.profile_action_target).setOnClickListener(target -> action.run());
        actions.addView(row);
    }

    private void showPersonal() {
        // TODO FR-017: Display nickname and masked phone number returned for the signed-in account.
        showMessage(R.string.profile_personal, getText(R.string.profile_personal_fields));
    }

    private void showDevices() {
        // TODO FR-002/PG-11: Show the current device and binding information authorized by Flask.
        showMessage(R.string.profile_devices, getText(R.string.profile_device_fields));
    }

    private void showEditChoices() {
        CharSequence[] options = {getText(R.string.profile_edit_phone), getText(R.string.profile_edit_password)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_edit)
                .setItems(options, (dialog, index) -> {
                    if (index == 0) showPhoneForm();
                    else showPasswordForm();
                })
                .setNegativeButton(R.string.profile_close, null)
                .show();
    }

    private void showPhoneForm() {
        // TODO FR-001: Wire sending the verification code and submitting the new number to the shared account service.
        showEditForm(R.string.profile_edit_phone, R.layout.dialog_profile_phone);
    }

    private void showPasswordForm() {
        // TODO FR-001: Submit the password change through the shared account service after authentication.
        showEditForm(R.string.profile_edit_password, R.layout.dialog_profile_password);
    }

    private void showEditForm(@StringRes int title, @LayoutRes int layout) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setView(layout)
                .setNegativeButton(R.string.profile_close, null)
                .setPositiveButton(R.string.profile_save, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    private void showSupport() {
        String qq = getString(R.string.profile_support_qq);
        if (qq.isEmpty()) {
            showMessage(R.string.profile_support, getText(R.string.profile_support_pending));
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_support)
                .setMessage(getString(R.string.profile_support_fields, getString(R.string.profile_support_name), qq))
                .setNegativeButton(R.string.profile_close, null)
                .setPositiveButton(R.string.profile_copy_qq, (dialog, which) -> {
                    ClipboardManager clipboard = requireContext().getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText("QQ", qq));
                    Toast.makeText(requireContext(), R.string.profile_qq_copied, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showVersion() {
        // TODO PG-12: Request the latest available version and compare it with the installed version code.
        showMessage(R.string.profile_check_updates,
                getString(R.string.profile_version_details, BuildConfig.VERSION_NAME));
    }

    private void showLogout() {
        // TODO FR-001/PG-12: Revoke the shared session, clear account state and return to sign-in.
        showMessage(R.string.profile_logout, getText(R.string.profile_logout_guest));
    }

    private void showMessage(@StringRes int title, CharSequence message) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.profile_close, null)
                .show();
    }
}

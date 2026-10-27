package id.jawa.feature.appstate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppPatchBuilderTest {

    @Test
    void contactAddPatchMatchesBaileysShape() {
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildAddOrEditContact(
            "111@s.whatsapp.net", "Jane Doe", "Jane", "jane", "222@lid", "111@s.whatsapp.net");
        assertThat(patch.index()).containsExactly("contact", "111@s.whatsapp.net");
        assertThat(patch.type()).isEqualTo("critical_unblock_low");
        assertThat(patch.apiVersion()).isEqualTo(2);
        assertThat(patch.operation()).isEqualTo(AppPatchBuilder.Operation.SET);
        assertThat(patch.syncAction().hasContactAction()).isTrue();
        assertThat(patch.syncAction().getContactAction().getFullName()).isEqualTo("Jane Doe");
        assertThat(patch.syncAction().getContactAction().getLidJid()).isEqualTo("222@lid");
    }

    @Test
    void contactRemovePatchUsesRemoveOperationAndEmptyAction() {
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net");
        assertThat(patch.operation()).isEqualTo(AppPatchBuilder.Operation.REMOVE);
        assertThat(patch.syncAction().hasContactAction()).isTrue();
        assertThat(patch.syncAction().getContactAction().hasFullName()).isFalse();
    }

    @Test
    void labelEditPatchMatchesBaileysShape() {
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildLabelEdit("42", "Work", 3, 7, false);
        assertThat(patch.index()).containsExactly("label_edit", "42");
        assertThat(patch.type()).isEqualTo("regular");
        assertThat(patch.apiVersion()).isEqualTo(3);
        assertThat(patch.syncAction().getLabelEditAction().getName()).isEqualTo("Work");
        assertThat(patch.syncAction().getLabelEditAction().getColor()).isEqualTo(3);
        assertThat(patch.syncAction().getLabelEditAction().getPredefinedId()).isEqualTo(7);
        assertThat(patch.syncAction().getLabelEditAction().getDeleted()).isFalse();
    }

    @Test
    void labelEditWithoutPredefinedIdLeavesFieldUnset() {
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildLabelEdit("42", "Work", 3, null, false);
        assertThat(patch.syncAction().getLabelEditAction().hasPredefinedId()).isFalse();
    }

    @Test
    void labelDeletePatchMarksDeleted() {
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildLabelDelete("42");
        assertThat(patch.syncAction().getLabelEditAction().getDeleted()).isTrue();
        assertThat(patch.index()).containsExactly("label_edit", "42");
    }

    @Test
    void chatAndMessageLabelPatchesMatchBaileysIndexes() {
        AppPatchBuilder.AppPatch chat = AppPatchBuilder.buildChatLabel("111@s.whatsapp.net", "42", true);
        assertThat(chat.index()).containsExactly("label_jid", "42", "111@s.whatsapp.net");
        assertThat(chat.syncAction().getLabelAssociationAction().getLabeled()).isTrue();

        AppPatchBuilder.AppPatch msg = AppPatchBuilder.buildMessageLabel(
            "111@s.whatsapp.net", "msg1", "42", false);
        assertThat(msg.index()).containsExactly(
            "label_message", "42", "111@s.whatsapp.net", "msg1", "0", "0");
        assertThat(msg.syncAction().getLabelAssociationAction().getLabeled()).isFalse();
    }

    @Test
    void blankInputsRejected() {
        assertThatThrownBy(() -> AppPatchBuilder.buildRemoveContact(" "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppPatchBuilder.buildLabelEdit("", "x", 1, null, false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppPatchBuilder.buildMessageLabel("111@s.whatsapp.net", "", "42", true))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

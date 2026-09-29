import { createSlice } from "@reduxjs/toolkit";

const initialState = {
    show: false,
    header: "",
    msgs: []
};

export const DialogSlice = createSlice({
    name: "dialog",
    initialState,
    reducers: {
        showDialog(state, action) {
            state.show = true;
            state.header = action.payload.header;
            state.msgs = action.payload.msgs;
        },
        hideDialog(state) {
            state.show = false;
            state.header = "";
            state.msgs = [];
        },
    }
});

export const { showDialog, hideDialog } = DialogSlice.actions;

export const selectDialog = (state) => state.dialog;

export default DialogSlice.reducer;


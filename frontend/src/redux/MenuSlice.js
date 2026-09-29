import { createSlice } from "@reduxjs/toolkit";

const initialState = {
    menuOpened: false,
    menuDashboardSidebar: false
};

export const menuSlice = createSlice({
    name: "menu",
    initialState,
    reducers: {
        openMenu: (state) => {
            state.menuOpened = true;
        },
        closeMenu: (state) => {
            state.menuOpened = false;
        },
        toggleSidebar: (state) => {
            state.menuDashboardSidebar = !state.menuDashboardSidebar;
        }
    }

})

export const { openMenu, closeMenu, toggleSidebar } = menuSlice.actions;

export const selectMenu = (state) => state.menu.menuOpened;
export const selectMenuSidebarDashboard = (state) => state.menu.menuDashboardSidebar;

export default menuSlice.reducer;


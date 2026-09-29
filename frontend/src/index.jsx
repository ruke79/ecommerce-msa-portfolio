import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";

import './index.css';
import { StrictMode } from "react";
import { API_BASE } from "./util/apiBase";
import { canonicalRedirectTarget } from "./util/canonicalOrigin";

// API 와 다른 출처(예: www 없는 주소)로 열렸으면 그리기 전에 API 출처로 옮긴다 — 그대로 두면 모든 API
// 호출이 교차 출처라 게이트웨이가 403 으로 거절한다(util/canonicalOrigin.js).
const canonical = canonicalRedirectTarget(API_BASE, window.location, process.env.NODE_ENV === "production");

if (canonical) {
    window.location.replace(canonical);
} else {
    const root = ReactDOM.createRoot(document.getElementById('root'));
    root.render(
        <StrictMode>
<App />
</StrictMode>
    );
}

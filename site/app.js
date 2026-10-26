/* ==========================================================================
   Forgeline — Traditional Landing Page Interactivity
   ========================================================================== */

(function () {
  "use strict";

  const GALLERY_SETS = {
    light: [
      {
        src: "./screenshots/trending_mixed_forges_light.webp",
        caption: "Trending · Mixed Forges (Light)",
      },
      {
        src: "./screenshots/changes_light.webp",
        caption: "Pull Request · Unified File Diffs (Light)",
      },
      {
        src: "./screenshots/feed_mixed_forges_light.webp",
        caption: "Chronological Feed · Mixed Forges (Light)",
      },
    ],
    dark: [
      {
        src: "./screenshots/trending_dark.webp",
        caption: "Trending · Daily Star Velocity (Dark)",
      },
      {
        src: "./screenshots/changes_dark.webp",
        caption: "Pull Request · Unified File Diffs (Dark)",
      },
      {
        src: "./screenshots/feed_dark.webp",
        caption: "Chronological Feed · Releases & Stars (Dark)",
      },
    ],
    amoled: [
      {
        src: "./screenshots/trending_resume_dark_amoled.webp",
        caption: "Trending · 'Where you left off' (AMOLED #000000)",
      },
      {
        src: "./screenshots/settings_dark_amoled.webp",
        caption: "Settings · True Black AMOLED (#000000)",
      },
      {
        src: "./screenshots/run_failed_dark.webp",
        caption: "CI Pipeline · Failing Job Log (AMOLED)",
      },
    ],
  };

  const THEME_ORDER = ["system", "light", "dark"];
  const THEME_LABELS = {
    system: "System",
    light: "Light",
    dark: "Dark",
  };

  let themeMode = "system";

  const themeToggleBtn = document.getElementById("theme-toggle-btn");
  const themeToggleLabel = document.getElementById("theme-toggle-label");
  const themeIcons = {
    system: document.getElementById("theme-icon-system"),
    light: document.getElementById("theme-icon-light"),
    dark: document.getElementById("theme-icon-dark"),
  };

  const navLinks = Array.from(document.querySelectorAll(".nav-link[data-nav-section]"));
  const stagePills = Array.from(document.querySelectorAll(".stage-pill[data-stage-target]"));
  const galleryModeBtns = Array.from(document.querySelectorAll(".gallery-mode-btn[data-gallery-mode]"));
  const darkMediaQuery = window.matchMedia
    ? window.matchMedia("(prefers-color-scheme: dark)")
    : null;

  /* ------------------------------------------------------------------------
     Page Theme Toggle (System -> Light -> Dark)
     ------------------------------------------------------------------------ */
  function resolveTheme(mode) {
    if (mode === "light" || mode === "dark") {
      return mode;
    }
    return darkMediaQuery && darkMediaQuery.matches ? "dark" : "light";
  }

  function applyThemeMode(mode) {
    themeMode = mode;
    document.documentElement.setAttribute("data-theme", resolveTheme(mode));

    if (themeToggleBtn) {
      themeToggleBtn.setAttribute(
        "aria-label",
        `Page theme: ${THEME_LABELS[mode]}. Click to switch theme.`
      );
    }
    if (themeToggleLabel) {
      themeToggleLabel.textContent = THEME_LABELS[mode];
    }
    Object.keys(themeIcons).forEach((key) => {
      const icon = themeIcons[key];
      if (!icon) return;
      icon.toggleAttribute("hidden", key !== mode);
    });
  }

  if (themeToggleBtn) {
    themeToggleBtn.addEventListener("click", () => {
      const idx = THEME_ORDER.indexOf(themeMode);
      applyThemeMode(THEME_ORDER[(idx + 1) % THEME_ORDER.length]);
    });
  }

  if (darkMediaQuery && typeof darkMediaQuery.addEventListener === "function") {
    darkMediaQuery.addEventListener("change", () => {
      if (themeMode === "system") {
        applyThemeMode("system");
      }
    });
  }

  /* ------------------------------------------------------------------------
     Header Active Section Underline (Scrollspy)
     ------------------------------------------------------------------------ */
  function setActiveNavSection(sectionId) {
    navLinks.forEach((link) => {
      link.classList.toggle("is-active", link.dataset.navSection === sectionId);
    });
  }

  function updateActiveSectionOnScroll() {
    const scrollPos = window.scrollY + 180;
    let activeId = "forges";

    navLinks.forEach((link) => {
      const sectionId = link.dataset.navSection;
      const sectionEl = document.getElementById(sectionId);
      if (sectionEl && sectionEl.offsetTop <= scrollPos) {
        activeId = sectionId;
      }
    });

    setActiveNavSection(activeId);
  }

  navLinks.forEach((link) => {
    link.addEventListener("click", () => {
      if (link.dataset.navSection) {
        setActiveNavSection(link.dataset.navSection);
      }
    });
  });

  window.addEventListener("scroll", updateActiveSectionOnScroll, { passive: true });

  /* ------------------------------------------------------------------------
     3-Phone Theme Gallery (Switches screenshots only, not page theme)
     ------------------------------------------------------------------------ */
  function setGalleryMode(mode) {
    const items = GALLERY_SETS[mode] || GALLERY_SETS.light;

    galleryModeBtns.forEach((btn) => {
      btn.setAttribute("aria-pressed", String(btn.dataset.galleryMode === mode));
    });

    items.forEach((item, idx) => {
      const num = idx + 1;
      const img = document.getElementById(`gallery-img-${num}`);
      const cap = document.getElementById(`gallery-cap-${num}`);
      if (img) {
        img.src = item.src;
        img.alt = `Forgeline Android app screenshot: ${item.caption}`;
      }
      if (cap) {
        cap.textContent = item.caption;
      }
    });
  }

  galleryModeBtns.forEach((btn) => {
    btn.addEventListener("click", () => {
      setGalleryMode(btn.dataset.galleryMode);
    });
  });

  /* ------------------------------------------------------------------------
     Spotlight Phone Stage Switchers
     ------------------------------------------------------------------------ */
  function activateStagePill(pill) {
    const { stageTarget, src, caption } = pill.dataset;

    const group = pill.closest(".stage-pills");
    if (group) {
      group.querySelectorAll(".stage-pill").forEach((btn) => {
        btn.setAttribute("aria-pressed", String(btn === pill));
      });
    }

    const img = document.getElementById(`${stageTarget}-img`);
    const cap = document.getElementById(`${stageTarget}-caption`);
    if (img && src) {
      img.src = src;
      img.alt = `Forgeline Android app screenshot: ${caption}`;
    }
    if (cap && caption) {
      cap.textContent = caption;
    }
  }

  stagePills.forEach((pill) => {
    pill.addEventListener("click", () => {
      activateStagePill(pill);
    });
  });

  applyThemeMode("system");
  updateActiveSectionOnScroll();
})();

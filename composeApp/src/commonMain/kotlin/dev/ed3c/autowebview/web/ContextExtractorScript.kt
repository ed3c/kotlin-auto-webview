package dev.ed3c.autowebview.web

object ContextExtractorScript {
    val source: String = """
        (() => {
          if (window.__kawInstalled) {
            window.__kawCaptureContext();
            return "already-installed";
          }
          window.__kawInstalled = true;

          const normalize = value => (value || "").replace(/\s+/g, " ").trim();
          const fnv1a = value => {
            let hash = 0x811c9dc5;
            for (let i = 0; i < value.length; i++) {
              hash ^= value.charCodeAt(i);
              hash = Math.imul(hash, 0x01000193);
            }
            return (hash >>> 0).toString(16).padStart(8, "0");
          };
          const sensitiveType = type => ["password", "cc-number", "cc-csc", "credit-card"].includes((type || "").toLowerCase());
          const accessibleName = el => normalize(
            el.getAttribute("aria-label") ||
            el.getAttribute("title") ||
            el.getAttribute("alt") ||
            el.getAttribute("placeholder") ||
            el.innerText
          ).slice(0, 240);

          const collectInteractive = () => Array.from(document.querySelectorAll(
            "button, a[href], input, textarea, select, [role=button], [role=link], [contenteditable=true]"
          )).filter(el => {
            const style = window.getComputedStyle(el);
            const rect = el.getBoundingClientRect();
            return style.visibility !== "hidden" && style.display !== "none" && rect.width > 1 && rect.height > 1 && !sensitiveType(el.type);
          }).slice(0, 300).map(el => {
            const rect = el.getBoundingClientRect();
            const name = accessibleName(el);
            const signature = [el.tagName, el.getAttribute("role"), el.id, el.getAttribute("name"), name, el.getAttribute("href")].join("|");
            return {
              fingerprint: fnv1a(signature),
              tag: el.tagName.toLowerCase(),
              role: el.getAttribute("role"),
              text: normalize(el.innerText).slice(0, 240),
              accessibleName: name,
              inputType: el.getAttribute("type"),
              rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height }
            };
          });

          const collectMainText = () => {
            const clone = document.body ? document.body.cloneNode(true) : document.documentElement.cloneNode(true);
            clone.querySelectorAll("script, style, noscript, svg, canvas, nav, footer, iframe, input[type=password]").forEach(node => node.remove());
            return normalize(clone.innerText || clone.textContent).slice(0, 20000);
          };

          window.__kawCaptureContext = () => {
            const payload = {
              url: window.location.href,
              title: document.title || window.location.hostname,
              markdown: collectMainText(),
              selection: normalize(window.getSelection ? window.getSelection().toString() : "").slice(0, 2000),
              capturedAtEpochMs: Date.now(),
              viewportWidth: window.innerWidth,
              viewportHeight: window.innerHeight,
              scrollX: window.scrollX,
              scrollY: window.scrollY,
              interactiveElements: collectInteractive()
            };
            if (window.kmpJsBridge && window.kmpJsBridge.callNative) {
              window.kmpJsBridge.callNative("PageContext", JSON.stringify(payload), null);
            }
            return payload;
          };

          let scheduled = false;
          const scheduleCapture = () => {
            if (scheduled) return;
            scheduled = true;
            window.setTimeout(() => {
              scheduled = false;
              window.__kawCaptureContext();
            }, 350);
          };
          window.addEventListener("scroll", scheduleCapture, { passive: true });
          document.addEventListener("selectionchange", scheduleCapture);
          new MutationObserver(scheduleCapture).observe(document.documentElement, { childList: true, subtree: true, characterData: true });
          window.__kawCaptureContext();
          return "installed";
        })();
    """.trimIndent()
}

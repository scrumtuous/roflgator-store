(function () {
    var BASE_URL_KEY = "storeDemoBaseUrl";
    var PAGES = [
        { id: "home", href: "index.html", label: "Home" },
        { id: "products", href: "products.html", label: "GET /api/products" },
        { id: "customers", href: "customers.html", label: "GET /api/customers" },
        { id: "purchase", href: "purchase.html", label: "GET /api/purchases" },
        { id: "report", href: "report.html", label: "GET /api/report" },
        { id: "sales", href: "sales.html", label: "POST /api/sales" },
        { id: "queue-submit", href: "queue-submit.html", label: "POST /queue/purchases" },
        { id: "queue-status", href: "queue-status.html", label: "GET /queue/purchases/{id}/status" },
        { id: "admin", href: "admin.html", label: "Admin / Troubleshoot" }
    ];

    function defaultBaseUrl() {
        if (window.location.origin && window.location.origin !== "null") {
            return window.location.origin;
        }
        return "http://localhost:8080";
    }

    function setOutput(text, isError) {
        var output = document.getElementById("output");
        if (!output) {
            return;
        }
        output.textContent = text;
        output.style.color = isError ? "#b00020" : "#111";
    }

    function setLoading(loading) {
        var button = document.getElementById("submitBtn");
        if (!button) {
            return;
        }
        button.disabled = loading;
        button.textContent = loading ? "Loading..." : "Send Request";
    }

    function getBaseUrl() {
        var input = document.getElementById("baseUrl");
        var fromInput = input ? input.value.trim() : "";
        if (fromInput) {
            localStorage.setItem(BASE_URL_KEY, fromInput);
            return fromInput;
        }
        var fromStorage = localStorage.getItem(BASE_URL_KEY);
        if (fromStorage) {
            return fromStorage;
        }
        return defaultBaseUrl();
    }

    function buildUrl(path, queryObject) {
        var url = getBaseUrl().replace(/\/$/, "") + "/" + path.replace(/^\//, "");
        if (!queryObject) {
            return url;
        }
        var params = new URLSearchParams(queryObject);
        var query = params.toString();
        return query ? url + "?" + query : url;
    }

    function renderNav(activePageId) {
        var nav = document.getElementById("nav");
        if (!nav) {
            return;
        }
        var html = PAGES.map(function (page) {
            if (page.id === activePageId) {
                return "<strong>" + page.label + "</strong>";
            }
            return '<a href="' + page.href + '">' + page.label + "</a>";
        }).join(" | ");
        nav.innerHTML = html;
    }

    function initBaseUrlInput() {
        var input = document.getElementById("baseUrl");
        if (!input) {
            return;
        }
        var saved = localStorage.getItem(BASE_URL_KEY);
        input.value = saved || defaultBaseUrl();
        input.addEventListener("change", function () {
            localStorage.setItem(BASE_URL_KEY, input.value.trim());
        });
    }

    function formatResponseBody(text) {
        if (!text) {
            return "(no response body)";
        }
        try {
            return JSON.stringify(JSON.parse(text), null, 2);
        } catch (err) {
            return text;
        }
    }

    window.StoreDemo = {
        buildUrl: buildUrl,
        formatResponseBody: formatResponseBody,
        getBaseUrl: getBaseUrl,
        initPage: function (activePageId) {
            renderNav(activePageId);
            initBaseUrlInput();
            setOutput("", false);
        },
        setLoading: setLoading,
        setOutput: setOutput
    };
})();
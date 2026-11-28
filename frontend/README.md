# Scribd Downloader - Frontend (Vercel & A2 Hosting Ready)

This directory contains the standalone production frontend for **Scribd Downloader**. It is pre-configured to connect to your live cloud backend on Render (`https://scribd-dl.onrender.com`).

---

## 🚀 Option 1: Deploy on Vercel (Recommended & 100% Free)

1. Go to **[vercel.com](https://vercel.com)** and sign in with GitHub.
2. Click **"Add New..."** -> **"Project"**.
3. Import your GitHub repository (`zamanalisandhu/Scribd-DL`).
4. In the **"Root Directory"** field, click **Edit** and select the `frontend` folder.
5. Click **"Deploy"**.
6. **Done!** Your site will be live at `https://your-project.vercel.app` in under 30 seconds.
7. You can attach your custom domain (`yourdomain.com`) in the Vercel Dashboard for free!

---

## 🌐 Option 2: Host on A2 Hosting (cPanel / Shared Hosting)

1. Log into your **cPanel** on A2 Hosting.
2. Open **File Manager** and navigate to your domain's folder (usually `public_html`).
3. Upload `index.html` from this `frontend` folder directly into `public_html`.
4. **Done!** Open your domain in any browser (`https://yourdomain.com`).

---

## ⚡ Smart Features Included
* **Auto-Wakeup:** The page automatically pings the Render backend as soon as a visitor loads the site. By the time they paste their Scribd URL, the backend is warm and ready!
* **100% Responsive & Dark/Light Mode:** Full mobile and desktop support with Lucide icons and TailwindCSS.
* **Multi-Format Support:** PDF, Word (DOCX), and TXT document downloads.

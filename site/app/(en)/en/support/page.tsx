import { languages } from "@/lib/paths";
import { LegalShell, Arrow } from "@/components/site-chrome";
export const metadata = {
  title: "Support",
  alternates: { languages: languages("support") },
};
export default function Page() {
  return (
    <LegalShell locale="en" page="support" title="Support">
      <p>Report bugs and suggest features through GitHub Issues.</p>
      <a
        className="button primary"
        href="https://github.com/Milix-M/Yorube/issues"
        target="_blank"
        rel="noreferrer"
      >
        GitHub Issues <Arrow />
      </a>
      <h2>Connection problems</h2>
      <ol>
        <li>Check that your device can reach the server URL.</li>
        <li>
          Verify the API key and, if required, the Cloudflare Access
          credentials.
        </li>
        <li>Save the settings, then run the connection test.</li>
        <li>
          For notification or background problems, check Android permissions and
          battery settings.
        </li>
      </ol>
      <h2>What to include in a report</h2>
      <p>
        Include the app version, Android version, steps to reproduce the
        problem, expected behavior and actual behavior. Review debug logs before
        sharing them.
      </p>
      <div className="notice-box">
        <strong>GitHub issues are public.</strong>
        <p>
          Do not paste API keys, Client Secrets, private conversations, personal
          information or internal server details.
        </p>
      </div>
      <h2>Server questions</h2>
      <p>
        For model charges, deleting server-side data and logs, or issues
        specific to your connection endpoint, contact the server operator or the
        relevant service provider.
      </p>
    </LegalShell>
  );
}

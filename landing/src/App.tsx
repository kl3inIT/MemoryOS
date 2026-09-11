import { Capabilities } from "@/sections/capabilities";
import { Deployment } from "@/sections/deployment";
import { Faq } from "@/sections/faq";
import { Footer } from "@/sections/footer";
import { Header } from "@/sections/header";
import { Hero } from "@/sections/hero";
import { HowItWorks } from "@/sections/how-it-works";
import { ProductHighlights } from "@/sections/product-highlights";
import { Roadmap } from "@/sections/roadmap";
import { TrustStrip } from "@/sections/trust-strip";

// How it works comes before Capabilities: the unpinned access gate at its end separates the two
// pinned scenes.
function App() {
  return (
    <>
      <Header />
      <main id="main">
        <Hero />
        <TrustStrip />
        <ProductHighlights />
        <HowItWorks />
        <Capabilities />
        <Deployment />
        <Roadmap />
        <Faq />
      </main>
      <Footer />
    </>
  );
}

export { App };

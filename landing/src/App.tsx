import { Assets } from "@/sections/assets";
import { Capabilities } from "@/sections/capabilities";
import { Deployment } from "@/sections/deployment";
import { Faq } from "@/sections/faq";
import { Footer } from "@/sections/footer";
import { Header } from "@/sections/header";
import { Hero } from "@/sections/hero";
import { HowItWorks } from "@/sections/how-it-works";
import { ProductHighlights } from "@/sections/product-highlights";
import { TrustStrip } from "@/sections/trust-strip";

function App() {
  return (
    <>
      <Header />
      <main id="main">
        {/* The hero fills the first screen below the sticky header (h-16 and its border), with the
            trust strip along its bottom edge. */}
        <div className="flex min-h-[calc(100svh-4rem-1px)] flex-col">
          <Hero />
          <TrustStrip />
        </div>
        <ProductHighlights />
        <Capabilities />
        <Assets />
        <HowItWorks />
        <Deployment />
        <Faq />
      </main>
      <Footer />
    </>
  );
}

export { App };

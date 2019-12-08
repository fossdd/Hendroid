package me.devsaki.hentoid.activities.sources;

import me.devsaki.hentoid.enums.Site;

public class MusesActivity extends BaseWebActivity {

    private static final String DOMAIN_FILTER = "8muses.com";
    private static final String[] GALLERY_FILTER = {"//www.8muses.com/comics/album/"};
//    private static final String[] DIRTY_ELEMENTS = {".c-tile:not([href])"}; // <-- even when removing empty tiles, ads are generated and force-inserted by the ad JS (!)

    Site getStartSite() {
        return Site.MUSES;
    }

    @Override
    protected CustomWebViewClient getWebClient() {
//        addDirtyElements(DIRTY_ELEMENTS);
        CustomWebViewClient client = new CustomWebViewClient(GALLERY_FILTER, this);
        client.restrictTo(DOMAIN_FILTER);
        return client;
    }
}

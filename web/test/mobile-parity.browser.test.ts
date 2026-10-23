import { afterAll, beforeAll, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { chromium, type Browser } from "playwright";
let browser:Browser;
let scripts:Record<string,string> = {};
const css = readFileSync(import.meta.dir+"/../src/styles/android-mobile.css","utf8")+ `
body{margin:0}.node-element,.absolute{position:absolute}.relative{position:relative}.w-full{width:100%}.h-full{height:100%}
[data-canvas-resize-handle]{position:absolute;width:32px;height:32px;z-index:99}
[data-canvas-resize-handle="bottom-right"]{right:-16px;bottom:-16px}
[data-canvas-resize-handle="top-right"]{right:-16px;top:-16px}
[data-canvas-resize-handle="bottom-left"]{left:-16px;bottom:-16px}
[data-canvas-resize-handle="top-left"]{left:-16px;top:-16px}
`;
beforeAll(async()=>{
    for(const platform of ["android","desktop"]){
        const build=await Bun.build({
            entrypoints:[import.meta.dir+"/fixtures/mobile-parity-harness.tsx"], target:"browser",format:"esm",
            define:{"import.meta.env":JSON.stringify({VITE_BEEFTV_PLATFORM:platform}),"process.env.NODE_ENV":'"production"',"__BEEFTV_HEAVY_MEDIA_ENABLED__":"true"},
            plugins:[{name:"alias",setup(builder){
                builder.onLoad({filter:/src\/components\/model-logo\.tsx$/},()=>({contents:"export function ModelLogo(){return null}",loader:"js"}));
                builder.onResolve({filter:/^@\//},args=>({path:Bun.resolveSync("../src/"+args.path.slice(2),import.meta.dir)}));
            }}]
        });
        if(!build.success)throw new Error(build.logs.join("\n"));
        scripts[platform]=await build.outputs[0].text();
    }
    browser=await chromium.launch({headless:true});
},60000);
afterAll(async()=>{await browser?.close()});
for(const platform of ["android","desktop"]){
    test(platform+" controls and real pointer resize",async()=>{
        const context=await browser.newContext({viewport:platform==="android"?{width:960,height:480}:{width:1440,height:900},hasTouch:platform==="android",isMobile:platform==="android"});
        const page=await context.newPage();
        await page.route("https://parity.test/**",route=>route.fulfill(new URL(route.request().url()).pathname==="/app.js"
          ?{contentType:"text/javascript",body:scripts[platform]}
          :{contentType:"text/html",body:'<meta name="viewport" content="width=device-width,initial-scale=1"><style>'+css+'</style><div id="root"></div><script type="module" src="/app.js"></script>'}));
        try{
            await page.goto("https://parity.test");
            const input=page.getByRole("textbox",{name:"模型地址"});
            await input.waitFor();
            const height=(await input.boundingBox())!.height;
            expect(height).toBeLessThanOrEqual(platform==="android"?32:40);
            if(platform==="android"){
                expect(await page.evaluate(()=>getComputedStyle(document.documentElement).fontSize)).toBe("13px");
                await page.locator("#comfortable").tap();
                expect(await page.evaluate(()=>getComputedStyle(document.documentElement).fontSize)).toBe("16px");
            }
            const handle=page.locator('[data-canvas-resize-handle="bottom-right"]');
            const box=(await handle.boundingBox())!;
            const x=box.x+box.width/2,y=box.y+box.height/2;
            if(platform==="android"){
                const cdp=await context.newCDPSession(page);
                await cdp.send("Input.dispatchTouchEvent",{type:"touchStart",touchPoints:[{x,y,id:1}]});
                await cdp.send("Input.dispatchTouchEvent",{type:"touchMove",touchPoints:[{x:x+60,y:y+40,id:1}]});
                await cdp.send("Input.dispatchTouchEvent",{type:"touchEnd",touchPoints:[]});
            }else{
                await page.mouse.move(x,y);await page.mouse.down();await page.mouse.move(x+60,y+40,{steps:3});await page.mouse.up();
            }
            await page.waitForFunction(()=>JSON.parse(document.querySelector("#size")!.textContent!).width>=379);
            const size=JSON.parse(await page.locator("#size").innerText());
            expect(size.width).toBeCloseTo(380,0);expect(size.height).toBeCloseTo(280,0);
            await page.mouse.move(x+150,y+100);
            expect(JSON.parse(await page.locator("#size").innerText())).toEqual(size);
        }finally{await context.close()}
    },30000);
}


# @capacitor-community/npe

NPE Wrapper that allows you to call the Java APIs for NPE

## Install

```bash
npm install @capacitor-community/npe
npx cap sync
```

## API

<docgen-index>

* [`extractStreamInfo(...)`](#extractstreaminfo)
* [Interfaces](#interfaces)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### extractStreamInfo(...)

```typescript
extractStreamInfo(options: { videoUrl: string; }) => Promise<StreamInfoResult>
```

Extract YouTube video stream information

| Param         | Type                               | Description             |
| ------------- | ---------------------------------- | ----------------------- |
| **`options`** | <code>{ videoUrl: string; }</code> | - The video URL options |

**Returns:** <code>Promise&lt;<a href="#streaminforesult">StreamInfoResult</a>&gt;</code>

--------------------


### Interfaces


#### StreamInfoResult

| Prop             | Type                                                                                                                                                                                                    |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`success`**    | <code>boolean</code>                                                                                                                                                                                    |
| **`error`**      | <code>string</code>                                                                                                                                                                                     |
| **`streamInfo`** | <code>{ title: string; duration: number; uploader: string; viewCount: number; thumbnailUrl: string; videoStreams: VideoStream[]; audioStreams: AudioStream[]; videoOnlyStreams: VideoStream[]; }</code> |


#### VideoStream

| Prop             | Type                |
| ---------------- | ------------------- |
| **`url`**        | <code>string</code> |
| **`format`**     | <code>string</code> |
| **`resolution`** | <code>string</code> |
| **`fps`**        | <code>number</code> |


#### AudioStream

| Prop          | Type                |
| ------------- | ------------------- |
| **`url`**     | <code>string</code> |
| **`format`**  | <code>string</code> |
| **`bitrate`** | <code>number</code> |

</docgen-api>

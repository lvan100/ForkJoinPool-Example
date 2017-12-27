import sun.misc.Unsafe;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.awt.image.BufferedImage.TYPE_INT_RGB;

public class App {

    private static Unsafe getUnsafe() throws NoSuchFieldException, IllegalAccessException {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    private static class Record {
        List<String> outList = new ArrayList<>();
        long threadID; // 线程ID
        long time;
    }

    private final static AtomicInteger currentID = new AtomicInteger();

    private static long currentTime() {
        return currentID.getAndIncrement();
    }

    // 按照线程ID收集每个线程的 Record
    private static Map<Long, List<Record>> threadRecordsMap = new ConcurrentHashMap<>();

    private static class CountTask extends RecursiveTask<Integer> {

        CountDownLatch latch;

        int start;
        int end;

        CountTask(int start, int end, CountDownLatch latch) {
            this.latch = latch;
            this.start = start;
            this.end = end;
        }

        @Override
        protected Integer compute() {

            try {
                latch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if (end - start < 20) {

                dumpQueues("#sum " + toString());

                int sum = 0;
                for (int i = start; i <= end; i++) {
                    sum += i;
                }
                return sum;
            }

            dumpQueues("#fork " + toString());

            int middle = start + (end - start) / 2;

            CountDownLatch latch1 = new CountDownLatch(1);
            CountTask c1 = new CountTask(start, middle, latch1);

            CountDownLatch latch2 = new CountDownLatch(1);
            CountTask c2 = new CountTask(middle + 1, end, latch2);

            c1.fork();
            dumpQueues("#c1.fork " + c1.toString());

            c2.fork();
            dumpQueues("#c2.fork " + c2.toString());

            latch1.countDown();
            latch2.countDown();

            int r1 = c1.join();
            int r2 = c2.join();

            dumpQueues("#result " + toString());

            return r1 + r2;
        }

        @Override
        public String toString() {
            return "[" + start + "," + end + "]";
        }

        private void dumpQueues(String tip) {

            long threadID = Thread.currentThread().getId();
            List<Record> recordList = threadRecordsMap.computeIfAbsent(threadID, k -> new ArrayList<>());

            Record record = new Record();
            recordList.add(record);

            record.threadID = threadID;
            record.time = currentTime();

            record.outList.add(record.time + " " + tip);

            try {
                Field wqs_f = ForkJoinPool.class.getDeclaredField("workQueues");
                wqs_f.setAccessible(true);

                for (Object wq : (Object[]) wqs_f.get(getPool())) {
                    if (wq == null) {
                        continue;
                    }

                    Field owner_f = wq.getClass().getDeclaredField("owner");
                    owner_f.setAccessible(true);

                    Thread owner = (Thread) owner_f.get(wq);

                    if (owner == null || owner.getId() != threadID) {
                        continue;
                    }

                    Field array_f = wq.getClass().getDeclaredField("array");
                    array_f.setAccessible(true);

                    ForkJoinTask[] array = (ForkJoinTask[]) array_f.get(wq);

                    Field base_f = wq.getClass().getDeclaredField("base");
                    base_f.setAccessible(true);

                    int base = base_f.getInt(wq);

                    Field top_f = wq.getClass().getDeclaredField("top");
                    top_f.setAccessible(true);

                    int top = top_f.getInt(wq);

                    if (array != null) {
                        Unsafe U = getUnsafe();

                        int m = array.length - 1;
                        for (int s = top - 1; s - base >= 0; s--) {
                            long j = ((m & s) << 2) + 16;
                            Object task = U.getObject(array, j);
                            if (task != null && task instanceof CountTask) {
                                record.outList.add(owner.getId() + " " + s + " " + task);
                            }
                        }
                    }

                    Field steal_f = wq.getClass().getDeclaredField("currentSteal");
                    steal_f.setAccessible(true);

                    ForkJoinTask steal = (ForkJoinTask) steal_f.get(wq);

                    if (steal != null) {
                        record.outList.set(0, record.outList.get(0) + " steal " + steal);
                    }
                }

            } catch (NoSuchFieldException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }

    }

    public static void main(String[] args) {

        CountDownLatch latch = new CountDownLatch(0);
        CountTask task = new CountTask(1, 60, latch);

        ForkJoinPool pool = new ForkJoinPool(3);
        Future<Integer> result = pool.submit(task);

        try {
            System.out.println(result.get());
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }

        dumpRecordToImage();
    }

    /**
     * 将输出记录输出到图像
     */
    private static void dumpRecordToImage() {

        Long[] threadIDs = threadRecordsMap.keySet().toArray(new Long[0]);
        Arrays.sort(threadIDs);

        List<Record> newQueue = new ArrayList<>();
        threadRecordsMap.forEach((id, queue) -> newQueue.addAll(queue));

        newQueue.sort((o1, o2) -> (int) (o1.time - o2.time));

        final int imageHeight = 800;

        BufferedImage image = new BufferedImage(240 * threadIDs.length, imageHeight, TYPE_INT_RGB);
        Graphics2D graphics = (Graphics2D) image.getGraphics();

        graphics.setColor(Color.white);
        graphics.fillRect(0, 0, image.getWidth(), imageHeight);

        graphics.setColor(Color.black);
        graphics.setStroke(new BasicStroke());

        for (int i = 0; i < threadIDs.length; i++) {
            int linePos = image.getWidth() / threadIDs.length * i;

            if (i > 0) {
                graphics.drawLine(linePos, 0, linePos, imageHeight);
            }

            graphics.drawString("线程 " + threadIDs[i], linePos + 90, 20);
        }

        Record[] records = newQueue.toArray(new Record[0]);

        int height = 20;
        for (Record r : records) {
            height += 15;

            int lastHeight = height;

            int i = Arrays.binarySearch(threadIDs, r.threadID);
            int linePos = image.getWidth() / threadIDs.length * i;

            List<String> outList = r.outList;
            for (int k = 0; k < outList.size(); k++) {
                height += 15;

                String aText = outList.get(k);
                graphics.drawString(aText, linePos + 12, height);

                if (k == 0) {
                    height += 5;
                }
            }

            graphics.drawRect(linePos + 10, lastHeight, 220, 15 * r.outList.size() + 10);
        }

        try {
            ImageIO.write(image, "jpg", new File("fk.jpg"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
